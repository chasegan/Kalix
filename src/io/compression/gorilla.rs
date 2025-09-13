use std::io::{self, Read, Write};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};

/// Bit writer for efficient bit-level operations
struct BitWriter {
    buffer: Vec<u8>,
    current_byte: u8,
    bit_count: u8,
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

    fn write_bits(&mut self, value: u64, num_bits: u8) {
        for i in (0..num_bits).rev() {
            let bit = (value >> i) & 1 == 1;
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
    bit_index: u8,
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
        let bit = (byte >> (7 - self.bit_index)) & 1 == 1;

        self.bit_index += 1;
        if self.bit_index == 8 {
            self.byte_index += 1;
            self.bit_index = 0;
        }

        Some(bit)
    }

    fn read_bits(&mut self, num_bits: u8) -> Option<u64> {
        let mut value = 0u64;
        for _ in 0..num_bits {
            if let Some(bit) = self.read_bit() {
                value = (value << 1) | (bit as u64);
            } else {
                return None;
            }
        }
        Some(value)
    }
}

/// Gorilla compression for timeseries data
pub struct GorillaCompressor {
    timestep: i64,
}

impl GorillaCompressor {
    /// Create a new compressor with the given constant timestep
    pub fn new(timestep: i64) -> Self {
        Self { timestep }
    }

    /// Compress a timeseries of (timestamp, f64) pairs
    pub fn compress_f64(&self, series: &[(i64, f64)]) -> io::Result<Vec<u8>> {
        if series.is_empty() {
            return Ok(Vec::new());
        }

        let mut writer = BitWriter::new();

        // Write header: timestep and first timestamp
        writer.write_bits(self.timestep as u64, 64);
        writer.write_bits(series[0].0 as u64, 64);
        writer.write_bits(series[0].1.to_bits(), 64);

        let mut prev_timestamp = series[0].0;
        let mut prev_value_bits = series[0].1.to_bits();
        let mut prev_delta = 0i64;

        for &(timestamp, value) in &series[1..] {
            self.compress_timestamp(&mut writer, timestamp, prev_timestamp, &mut prev_delta);
            self.compress_value_f64(&mut writer, value, prev_value_bits);

            prev_timestamp = timestamp;
            prev_value_bits = value.to_bits();
        }

        Ok(writer.finish())
    }

    /// Compress a timeseries of (timestamp, f32) pairs
    pub fn compress_f32(&self, series: &[(i64, f32)]) -> io::Result<Vec<u8>> {
        if series.is_empty() {
            return Ok(Vec::new());
        }

        let mut writer = BitWriter::new();

        // Write header: timestep and first timestamp
        writer.write_bits(self.timestep as u64, 64);
        writer.write_bits(series[0].0 as u64, 64);
        writer.write_bits(series[0].1.to_bits() as u64, 32);

        let mut prev_timestamp = series[0].0;
        let mut prev_value_bits = series[0].1.to_bits();
        let mut prev_delta = 0i64;

        for &(timestamp, value) in &series[1..] {
            self.compress_timestamp(&mut writer, timestamp, prev_timestamp, &mut prev_delta);
            self.compress_value_f32(&mut writer, value, prev_value_bits);

            prev_timestamp = timestamp;
            prev_value_bits = value.to_bits();
        }

        Ok(writer.finish())
    }

    fn compress_timestamp(&self, writer: &mut BitWriter, timestamp: i64, prev_timestamp: i64, prev_delta: &mut i64) {
        let delta = timestamp - prev_timestamp;

        if delta == self.timestep {
            // Common case: regular timestep
            writer.write_bit(false);
        } else if delta == *prev_delta {
            // Delta of deltas is 0
            writer.write_bit(true);
            writer.write_bit(false);
        } else {
            // Need to encode delta of deltas
            let delta_of_deltas = delta - *prev_delta;
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

            *prev_delta = delta;
        }
    }

    fn compress_value_f64(&self, writer: &mut BitWriter, value: f64, prev_value_bits: u64) {
        let value_bits = value.to_bits();

        if value_bits == prev_value_bits {
            // Same value
            writer.write_bit(false);
        } else {
            writer.write_bit(true);
            let xor = value_bits ^ prev_value_bits;
            let leading_zeros = xor.leading_zeros() as u8;
            let trailing_zeros = xor.trailing_zeros() as u8;
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

    fn compress_value_f32(&self, writer: &mut BitWriter, value: f32, prev_value_bits: u32) {
        let value_bits = value.to_bits();

        if value_bits == prev_value_bits {
            // Same value
            writer.write_bit(false);
        } else {
            writer.write_bit(true);
            let xor = value_bits ^ prev_value_bits;
            let leading_zeros = xor.leading_zeros() as u8;
            let trailing_zeros = xor.trailing_zeros() as u8;
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

    /// Decompress f64 timeseries data
    pub fn decompress_f64(&self, compressed: &[u8]) -> io::Result<Vec<(i64, f64)>> {
        if compressed.is_empty() {
            return Ok(Vec::new());
        }

        let mut reader = BitReader::new(compressed);
        let mut result = Vec::new();

        // Read header
        let timestep = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))? as i64;
        let first_timestamp = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))? as i64;
        let first_value = f64::from_bits(reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))?);

        result.push((first_timestamp, first_value));

        let mut prev_timestamp = first_timestamp;
        let mut prev_value_bits = first_value.to_bits();
        let mut prev_delta = timestep;

        while let Some(control_bit) = reader.read_bit() {
            // Decompress timestamp
            let timestamp = if !control_bit {
                prev_timestamp + timestep
            } else if let Some(delta_control) = reader.read_bit() {
                if !delta_control {
                    prev_timestamp + prev_delta
                } else {
                    let delta_of_deltas = self.read_delta_of_deltas(&mut reader)?;
                    prev_delta += delta_of_deltas;
                    prev_timestamp + prev_delta
                }
            } else {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "Unexpected end of data"));
            };

            // Decompress value
            let value = if let Some(value_control) = reader.read_bit() {
                if !value_control {
                    // Same value
                    f64::from_bits(prev_value_bits)
                } else if let Some(encoding_control) = reader.read_bit() {
                    if !encoding_control {
                        // Compressed XOR encoding
                        let leading_zeros = reader.read_bits(5).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as u8;
                        let meaningful_bits = reader.read_bits(6).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as u8;

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
                        f64::from_bits(reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))?)
                    }
                } else {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "Unexpected end of data"));
                }
            } else {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "Unexpected end of data"));
            };

            result.push((timestamp, value));
            prev_timestamp = timestamp;
            prev_value_bits = value.to_bits();
        }

        Ok(result)
    }

    /// Decompress f32 timeseries data
    pub fn decompress_f32(&self, compressed: &[u8]) -> io::Result<Vec<(i64, f32)>> {
        if compressed.is_empty() {
            return Ok(Vec::new());
        }

        let mut reader = BitReader::new(compressed);
        let mut result = Vec::new();

        // Read header
        let timestep = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))? as i64;
        let first_timestamp = reader.read_bits(64).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))? as i64;
        let first_value = f32::from_bits(reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid header"))? as u32);

        result.push((first_timestamp, first_value));

        let mut prev_timestamp = first_timestamp;
        let mut prev_value_bits = first_value.to_bits();
        let mut prev_delta = timestep;

        while let Some(control_bit) = reader.read_bit() {
            // Decompress timestamp (same logic as f64)
            let timestamp = if !control_bit {
                prev_timestamp + timestep
            } else if let Some(delta_control) = reader.read_bit() {
                if !delta_control {
                    prev_timestamp + prev_delta
                } else {
                    let delta_of_deltas = self.read_delta_of_deltas(&mut reader)?;
                    prev_delta += delta_of_deltas;
                    prev_timestamp + prev_delta
                }
            } else {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "Unexpected end of data"));
            };

            // Decompress value (adapted for f32)
            let value = if let Some(value_control) = reader.read_bit() {
                if !value_control {
                    // Same value
                    f32::from_bits(prev_value_bits)
                } else if let Some(encoding_control) = reader.read_bit() {
                    if !encoding_control {
                        // Compressed XOR encoding
                        let leading_zeros = reader.read_bits(5).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as u8;
                        let meaningful_bits = reader.read_bits(6).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as u8;

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
                        f32::from_bits(reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid value encoding"))? as u32)
                    }
                } else {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "Unexpected end of data"));
                }
            } else {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "Unexpected end of data"));
            };

            result.push((timestamp, value));
            prev_timestamp = timestamp;
            prev_value_bits = value.to_bits();
        }

        Ok(result)
    }

    fn read_delta_of_deltas(&self, reader: &mut BitReader) -> io::Result<i64> {
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
            _ => unreachable!(),
        }
    }

    /// Compress and encode as base64 for JSON embedding
    pub fn compress_f64_base64(&self, series: &[(i64, f64)]) -> io::Result<String> {
        let compressed = self.compress_f64(series)?;
        Ok(BASE64.encode(&compressed))
    }

    /// Compress and encode as base64 for JSON embedding
    pub fn compress_f32_base64(&self, series: &[(i64, f32)]) -> io::Result<String> {
        let compressed = self.compress_f32(series)?;
        Ok(BASE64.encode(&compressed))
    }

    /// Decompress from base64
    pub fn decompress_f64_base64(&self, base64_data: &str) -> io::Result<Vec<(i64, f64)>> {
        let compressed = BASE64.decode(base64_data)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("Base64 decode error: {}", e)))?;
        self.decompress_f64(&compressed)
    }

    /// Decompress from base64
    pub fn decompress_f32_base64(&self, base64_data: &str) -> io::Result<Vec<(i64, f32)>> {
        let compressed = BASE64.decode(base64_data)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("Base64 decode error: {}", e)))?;
        self.decompress_f32(&compressed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_regular_series_f64() {
        let compressor = GorillaCompressor::new(1000); // 1 second timestep
        let series = vec![
            (1000, 1.0),
            (2000, 2.0),
            (3000, 3.0),
            (4000, 4.0),
            (5000, 5.0),
        ];

        let compressed = compressor.compress_f64(&series).unwrap();
        let decompressed = compressor.decompress_f64(&compressed).unwrap();

        assert_eq!(series, decompressed);
    }

    #[test]
    fn test_repeated_values_f64() {
        let compressor = GorillaCompressor::new(1000);
        let series = vec![
            (1000, 42.0),
            (2000, 42.0),
            (3000, 42.0),
            (4000, 43.0),
            (5000, 42.0),
        ];

        let compressed = compressor.compress_f64(&series).unwrap();
        let decompressed = compressor.decompress_f64(&compressed).unwrap();

        assert_eq!(series, decompressed);
    }

    #[test]
    fn test_special_values_f64() {
        let compressor = GorillaCompressor::new(1000);
        let series = vec![
            (1000, 0.0),
            (2000, f64::NAN),
            (3000, f64::INFINITY),
            (4000, f64::NEG_INFINITY),
            (5000, -0.0),
        ];

        let compressed = compressor.compress_f64(&series).unwrap();
        let decompressed = compressor.decompress_f64(&compressed).unwrap();

        // NaN comparison requires special handling
        assert_eq!(decompressed.len(), series.len());
        assert_eq!(decompressed[0], (1000, 0.0));
        assert!(decompressed[1].1.is_nan());
        assert_eq!(decompressed[2], (3000, f64::INFINITY));
        assert_eq!(decompressed[3], (4000, f64::NEG_INFINITY));
        assert_eq!(decompressed[4], (5000, -0.0));
    }

    #[test]
    fn test_f32_compression() {
        let compressor = GorillaCompressor::new(500);
        let series = vec![
            (500, 1.5f32),
            (1000, 2.5f32),
            (1500, 2.5f32), // repeated value
            (2000, f32::NAN),
            (2500, 0.0f32),
        ];

        let compressed = compressor.compress_f32(&series).unwrap();
        let decompressed = compressor.decompress_f32(&compressed).unwrap();

        assert_eq!(decompressed.len(), series.len());
        assert_eq!(decompressed[0], (500, 1.5f32));
        assert_eq!(decompressed[1], (1000, 2.5f32));
        assert_eq!(decompressed[2], (1500, 2.5f32));
        assert!(decompressed[3].1.is_nan());
        assert_eq!(decompressed[4], (2500, 0.0f32));
    }

    #[test]
    fn test_base64_encoding() {
        let compressor = GorillaCompressor::new(1000);
        let series = vec![
            (1000, 1.0),
            (2000, 2.0),
            (3000, 2.0), // repeated
            (4000, 4.0),
        ];

        let base64_compressed = compressor.compress_f64_base64(&series).unwrap();
        let decompressed = compressor.decompress_f64_base64(&base64_compressed).unwrap();

        assert_eq!(series, decompressed);
    }

    #[test]
    fn test_empty_series() {
        let compressor = GorillaCompressor::new(1000);
        let series: Vec<(i64, f64)> = vec![];

        let compressed = compressor.compress_f64(&series).unwrap();
        let decompressed = compressor.decompress_f64(&compressed).unwrap();

        assert_eq!(series, decompressed);
    }
}