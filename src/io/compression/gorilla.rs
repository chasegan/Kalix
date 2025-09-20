/// Custom Gorilla compression implementation matching the Java GorillaCompressor
/// This implements the exact same bit-level encoding as the Java version for compatibility

use std::io;

#[derive(Debug, Clone)]
pub struct TimeValueDouble {
    pub timestamp: u64,
    pub value: f64,
}

impl TimeValueDouble {
    pub fn new(timestamp: u64, value: f64) -> Self {
        Self { timestamp, value }
    }
}

#[derive(Debug, Clone)]
pub struct TimeValueFloat {
    pub timestamp: u64,
    pub value: f32,
}

impl TimeValueFloat {
    pub fn new(timestamp: u64, value: f32) -> Self {
        Self { timestamp, value }
    }
}

/// Bit writer for efficient bit-level operations
struct BitWriter {
    buffer: Vec<u8>,
    current_byte: u8,
    bit_count: usize,
}

impl BitWriter {
    fn new() -> Self {
        Self {
            buffer: Vec::new(),
            current_byte: 0,
            bit_count: 0,
        }
    }

    fn write_bit(&mut self, bit: bool) {
        if bit {
            self.current_byte |= 1 << (7 - self.bit_count);
        }
        self.bit_count += 1;

        if self.bit_count == 8 {
            self.buffer.push(self.current_byte);
            self.current_byte = 0;
            self.bit_count = 0;
        }
    }

    fn write_bits(&mut self, value: u64, num_bits: usize) {
        for i in (0..num_bits).rev() {
            let bit = ((value >> i) & 1) == 1;
            self.write_bit(bit);
        }
    }

    fn finish(mut self) -> Vec<u8> {
        if self.bit_count > 0 {
            self.buffer.push(self.current_byte);
        }
        self.buffer
    }
}

/// Bit reader for efficient bit-level operations
struct BitReader<'a> {
    data: &'a [u8],
    byte_index: usize,
    bit_index: usize,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self {
            data,
            byte_index: 0,
            bit_index: 0,
        }
    }

    fn read_bit(&mut self) -> Option<bool> {
        if self.byte_index >= self.data.len() {
            return None;
        }

        let byte = self.data[self.byte_index];
        let bit = ((byte >> (7 - self.bit_index)) & 1) == 1;

        self.bit_index += 1;
        if self.bit_index == 8 {
            self.byte_index += 1;
            self.bit_index = 0;
        }

        Some(bit)
    }

    fn read_bits(&mut self, num_bits: usize) -> Option<u64> {
        let mut value = 0u64;
        for _ in 0..num_bits {
            let bit = self.read_bit()?;
            value = (value << 1) | if bit { 1 } else { 0 };
        }
        Some(value)
    }
}

pub struct GorillaCompressor {
    timestep: u64,
}

impl GorillaCompressor {
    pub fn new(timestep: u64) -> Self {
        Self { timestep }
    }

    /// Compress a timeseries of double values
    pub fn compress_double(&self, series: &[TimeValueDouble]) -> Result<Vec<u8>, io::Error> {
        if series.is_empty() {
            return Ok(Vec::new());
        }

        let mut writer = BitWriter::new();

        // Write header: timestep, count, and first timestamp/value
        writer.write_bits(self.timestep, 64);
        writer.write_bits(series.len() as u64, 32);
        writer.write_bits(series[0].timestamp, 64);
        writer.write_bits(series[0].value.to_bits(), 64);

        let mut prev_timestamp = series[0].timestamp;
        let mut prev_value_bits = series[0].value.to_bits();
        let mut prev_delta = 0u64;

        for point in &series[1..] {
            prev_delta = self.compress_timestamp(&mut writer, point.timestamp, prev_timestamp, prev_delta);
            self.compress_value_double(&mut writer, point.value, prev_value_bits);

            prev_timestamp = point.timestamp;
            prev_value_bits = point.value.to_bits();
        }

        Ok(writer.finish())
    }

    /// Compress a timeseries of float values
    pub fn compress_float(&self, series: &[TimeValueFloat]) -> Result<Vec<u8>, io::Error> {
        if series.is_empty() {
            return Ok(Vec::new());
        }

        let mut writer = BitWriter::new();

        // Write header: timestep, count, and first timestamp/value
        writer.write_bits(self.timestep, 64);
        writer.write_bits(series.len() as u64, 32);
        writer.write_bits(series[0].timestamp, 64);
        writer.write_bits(series[0].value.to_bits() as u64, 32);

        let mut prev_timestamp = series[0].timestamp;
        let mut prev_value_bits = series[0].value.to_bits();
        let mut prev_delta = 0u64;

        for point in &series[1..] {
            prev_delta = self.compress_timestamp(&mut writer, point.timestamp, prev_timestamp, prev_delta);
            self.compress_value_float(&mut writer, point.value, prev_value_bits);

            prev_timestamp = point.timestamp;
            prev_value_bits = point.value.to_bits();
        }

        Ok(writer.finish())
    }

    fn compress_timestamp(&self, writer: &mut BitWriter, timestamp: u64, prev_timestamp: u64, prev_delta: u64) -> u64 {
        let delta = timestamp - prev_timestamp;

        if delta == self.timestep {
            // Common case: regular timestep
            writer.write_bit(false);
            prev_delta
        } else if delta == prev_delta {
            // Delta of deltas is 0
            writer.write_bit(true);
            writer.write_bit(false);
            prev_delta
        } else {
            // Need to encode delta of deltas
            let delta_of_deltas = (delta as i64) - (prev_delta as i64);
            writer.write_bit(true);
            writer.write_bit(true);

            if delta_of_deltas >= -63 && delta_of_deltas <= 64 {
                writer.write_bits(0, 2); // 7-bit encoding
                writer.write_bits((delta_of_deltas + 63) as u64, 7);
            } else if delta_of_deltas >= -255 && delta_of_deltas <= 256 {
                writer.write_bits(1, 2); // 9-bit encoding
                writer.write_bits((delta_of_deltas + 255) as u64, 9);
            } else if delta_of_deltas >= -2047 && delta_of_deltas <= 2048 {
                writer.write_bits(2, 2); // 12-bit encoding
                writer.write_bits((delta_of_deltas + 2047) as u64, 12);
            } else {
                writer.write_bits(3, 2); // 32-bit encoding
                writer.write_bits(delta_of_deltas as u64, 32);
            }

            delta
        }
    }

    fn compress_value_double(&self, writer: &mut BitWriter, value: f64, prev_value_bits: u64) {
        let value_bits = value.to_bits();

        if value_bits == prev_value_bits {
            // Same value
            writer.write_bit(false);
        } else {
            writer.write_bit(true);
            let xor = value_bits ^ prev_value_bits;
            let leading_zeros = xor.leading_zeros() as usize;
            let trailing_zeros = xor.trailing_zeros() as usize;
            let meaningful_bits = 64 - leading_zeros - trailing_zeros;

            if leading_zeros >= 5 && meaningful_bits <= 6 {
                // Use control bit pattern for common case
                writer.write_bit(false);
                writer.write_bits(leading_zeros as u64, 5);
                writer.write_bits(meaningful_bits as u64, 6);
                if meaningful_bits > 0 {
                    writer.write_bits(xor >> trailing_zeros, meaningful_bits);
                }
            } else {
                // Fallback: store all 64 bits
                writer.write_bit(true);
                writer.write_bits(value_bits, 64);
            }
        }
    }

    fn compress_value_float(&self, writer: &mut BitWriter, value: f32, prev_value_bits: u32) {
        let value_bits = value.to_bits();

        if value_bits == prev_value_bits {
            // Same value
            writer.write_bit(false);
        } else {
            writer.write_bit(true);
            let xor = value_bits ^ prev_value_bits;
            let leading_zeros = xor.leading_zeros() as usize;
            let trailing_zeros = xor.trailing_zeros() as usize;
            let meaningful_bits = 32 - leading_zeros - trailing_zeros;

            if leading_zeros >= 5 && meaningful_bits <= 6 {
                // Use control bit pattern for common case
                writer.write_bit(false);
                writer.write_bits(leading_zeros as u64, 5);
                writer.write_bits(meaningful_bits as u64, 6);
                if meaningful_bits > 0 {
                    writer.write_bits((xor >> trailing_zeros) as u64, meaningful_bits);
                }
            } else {
                // Fallback: store all 32 bits
                writer.write_bit(true);
                writer.write_bits(value_bits as u64, 32);
            }
        }
    }

    /// Decompress double timeseries data
    pub fn decompress_double(&self, compressed: &[u8]) -> Result<Vec<TimeValueDouble>, io::Error> {
        if compressed.is_empty() {
            return Ok(Vec::new());
        }

        let mut reader = BitReader::new(compressed);
        let mut result = Vec::new();

        // Read header
        let timestep = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?;
        let count = reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?;
        let first_timestamp = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?;
        let first_value_bits = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?;

        let first_value = f64::from_bits(first_value_bits);
        result.push(TimeValueDouble::new(first_timestamp, first_value));

        let mut prev_timestamp = first_timestamp;
        let mut prev_value_bits = first_value_bits;
        let mut prev_delta = 0u64;

        // Read remaining data points (count - 1 since we already have the first)
        for _ in 1..count {
            let control_bit = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;

            // Decompress timestamp
            let timestamp = if !control_bit {
                // Use signed arithmetic to handle negative timestamps
                let prev_signed = prev_timestamp as i64;
                let timestep_signed = timestep as i64;
                (prev_signed.wrapping_add(timestep_signed)) as u64
            } else {
                let delta_control = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;
                if !delta_control {
                    // Use signed arithmetic to handle negative timestamps
                    let prev_signed = prev_timestamp as i64;
                    let delta_signed = prev_delta as i64;
                    (prev_signed.wrapping_add(delta_signed)) as u64
                } else {
                    let delta_of_deltas = self.read_delta_of_deltas(&mut reader)?;
                    let new_delta_signed = (prev_delta as i64) + delta_of_deltas;
                    prev_delta = new_delta_signed as u64;

                    // Handle timestamp addition with signed arithmetic to avoid overflow
                    let prev_timestamp_signed = prev_timestamp as i64;
                    let delta_signed = prev_delta as i64;
                    let new_timestamp_signed = prev_timestamp_signed.wrapping_add(delta_signed);
                    new_timestamp_signed as u64
                }
            };

            // Decompress value
            let value_control = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;

            let value = if !value_control {
                // Same value
                f64::from_bits(prev_value_bits)
            } else {
                let encoding_control = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;

                if !encoding_control {
                    // Compressed XOR encoding
                    let leading_zeros = reader.read_bits(5).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as usize;
                    let meaningful_bits = reader.read_bits(6).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as usize;

                    if meaningful_bits == 0 {
                        f64::from_bits(prev_value_bits)
                    } else {
                        let meaningful_value = reader.read_bits(meaningful_bits).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))?;
                        let trailing_zeros = 64 - leading_zeros - meaningful_bits;
                        let xor = meaningful_value << trailing_zeros;
                        f64::from_bits(prev_value_bits ^ xor)
                    }
                } else {
                    // Full 64-bit value
                    let value_bits = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))?;
                    f64::from_bits(value_bits)
                }
            };

            result.push(TimeValueDouble::new(timestamp, value));
            prev_timestamp = timestamp;
            prev_value_bits = value.to_bits();
        }

        Ok(result)
    }

    /// Decompress float timeseries data
    pub fn decompress_float(&self, compressed: &[u8]) -> Result<Vec<TimeValueFloat>, io::Error> {
        if compressed.is_empty() {
            return Ok(Vec::new());
        }

        let mut reader = BitReader::new(compressed);
        let mut result = Vec::new();

        // Read header
        let timestep = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?;
        let count = reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?;
        let first_timestamp = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?;
        let first_value_bits = reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))? as u32;

        let first_value = f32::from_bits(first_value_bits);
        result.push(TimeValueFloat::new(first_timestamp, first_value));

        let mut prev_timestamp = first_timestamp;
        let mut prev_value_bits = first_value_bits;
        let mut prev_delta = 0u64;

        // Read remaining data points (count - 1 since we already have the first)
        for _ in 1..count {
            let control_bit = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;

            // Decompress timestamp (same logic as double)
            let timestamp = if !control_bit {
                // Use signed arithmetic to handle negative timestamps
                let prev_signed = prev_timestamp as i64;
                let timestep_signed = timestep as i64;
                (prev_signed.wrapping_add(timestep_signed)) as u64
            } else {
                let delta_control = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;
                if !delta_control {
                    // Use signed arithmetic to handle negative timestamps
                    let prev_signed = prev_timestamp as i64;
                    let delta_signed = prev_delta as i64;
                    (prev_signed.wrapping_add(delta_signed)) as u64
                } else {
                    let delta_of_deltas = self.read_delta_of_deltas(&mut reader)?;
                    let new_delta_signed = (prev_delta as i64) + delta_of_deltas;
                    prev_delta = new_delta_signed as u64;

                    // Handle timestamp addition with signed arithmetic to avoid overflow
                    let prev_timestamp_signed = prev_timestamp as i64;
                    let delta_signed = prev_delta as i64;
                    let new_timestamp_signed = prev_timestamp_signed.wrapping_add(delta_signed);
                    new_timestamp_signed as u64
                }
            };

            // Decompress value (adapted for float)
            let value_control = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;

            let value = if !value_control {
                // Same value
                f32::from_bits(prev_value_bits)
            } else {
                let encoding_control = reader.read_bit().ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "Unexpected end of data"))?;

                if !encoding_control {
                    // Compressed XOR encoding
                    let leading_zeros = reader.read_bits(5).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as usize;
                    let meaningful_bits = reader.read_bits(6).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as usize;

                    if meaningful_bits == 0 {
                        f32::from_bits(prev_value_bits)
                    } else {
                        let meaningful_value = reader.read_bits(meaningful_bits).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as u32;
                        let trailing_zeros = 32 - leading_zeros - meaningful_bits;
                        let xor = meaningful_value << trailing_zeros;
                        f32::from_bits(prev_value_bits ^ xor)
                    }
                } else {
                    // Full 32-bit value
                    let value_bits = reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as u32;
                    f32::from_bits(value_bits)
                }
            };

            result.push(TimeValueFloat::new(timestamp, value));
            prev_timestamp = timestamp;
            prev_value_bits = value.to_bits();
        }

        Ok(result)
    }

    fn read_delta_of_deltas(&self, reader: &mut BitReader) -> Result<i64, io::Error> {
        let encoding_type = reader.read_bits(2).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid delta encoding"))?;

        match encoding_type {
            0 => {
                // 7-bit encoding
                let value = reader.read_bits(7).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid delta encoding"))?;
                Ok((value as i64) - 63)
            }
            1 => {
                // 9-bit encoding
                let value = reader.read_bits(9).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid delta encoding"))?;
                Ok((value as i64) - 255)
            }
            2 => {
                // 12-bit encoding
                let value = reader.read_bits(12).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid delta encoding"))?;
                Ok((value as i64) - 2047)
            }
            3 => {
                // 32-bit encoding
                let value = reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid delta encoding"))?;
                Ok(value as i64)
            }
            _ => Err(io::Error::new(io::ErrorKind::InvalidData, "Invalid encoding type")),
        }
    }

    // Utility methods for easy creation of time-value pairs
    pub fn double_point(timestamp: u64, value: f64) -> TimeValueDouble {
        TimeValueDouble::new(timestamp, value)
    }

    pub fn float_point(timestamp: u64, value: f32) -> TimeValueFloat {
        TimeValueFloat::new(timestamp, value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_regular_series_double() {
        let compressor = GorillaCompressor::new(1000);
        let series = vec![
            TimeValueDouble::new(1000, 1.0),
            TimeValueDouble::new(2000, 2.0),
            TimeValueDouble::new(3000, 3.0),
            TimeValueDouble::new(4000, 4.0),
            TimeValueDouble::new(5000, 5.0),
        ];

        let compressed = compressor.compress_double(&series).unwrap();
        let decompressed = compressor.decompress_double(&compressed).unwrap();

        assert_eq!(series.len(), decompressed.len());
        for (original, decompressed) in series.iter().zip(decompressed.iter()) {
            assert_eq!(original.timestamp, decompressed.timestamp);
            assert_eq!(original.value, decompressed.value);
        }
    }

    #[test]
    fn test_repeated_values_double() {
        let compressor = GorillaCompressor::new(1000);
        let series = vec![
            TimeValueDouble::new(1000, 42.0),
            TimeValueDouble::new(2000, 42.0),
            TimeValueDouble::new(3000, 42.0),
            TimeValueDouble::new(4000, 43.0),
            TimeValueDouble::new(5000, 42.0),
        ];

        let compressed = compressor.compress_double(&series).unwrap();
        let decompressed = compressor.decompress_double(&compressed).unwrap();

        assert_eq!(series.len(), decompressed.len());
        for (original, decompressed) in series.iter().zip(decompressed.iter()) {
            assert_eq!(original.timestamp, decompressed.timestamp);
            assert_eq!(original.value, decompressed.value);
        }
    }

    #[test]
    fn test_float_compression() {
        let compressor = GorillaCompressor::new(500);
        let series = vec![
            TimeValueFloat::new(500, 1.5),
            TimeValueFloat::new(1000, 2.5),
            TimeValueFloat::new(1500, 2.5), // repeated value
            TimeValueFloat::new(2000, 0.0),
        ];

        let compressed = compressor.compress_float(&series).unwrap();
        let decompressed = compressor.decompress_float(&compressed).unwrap();

        assert_eq!(series.len(), decompressed.len());
        for (original, decompressed) in series.iter().zip(decompressed.iter()) {
            assert_eq!(original.timestamp, decompressed.timestamp);
            assert_eq!(original.value, decompressed.value);
        }
    }

    #[test]
    fn test_empty_series() {
        let compressor = GorillaCompressor::new(1000);
        let series: Vec<TimeValueDouble> = Vec::new();

        let compressed = compressor.compress_double(&series).unwrap();
        let decompressed = compressor.decompress_double(&compressed).unwrap();

        assert_eq!(series.len(), decompressed.len());
    }
}