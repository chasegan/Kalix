/// Custom Gorilla compression implementation matching the Java GorillaCompressor
/// (kalixide .../io/compression/gorilla/GorillaCompressor.java). This implements the
/// exact same bit-level encoding as the Java version for compatibility — any change
/// to the encoding here must be mirrored there in the same commit.
///
/// Value encoding (per changed value): 1 control bit, then either
///   0 + 5-bit leading-zero count (clamped to 31) + 6-bit meaningful-bit count
///     + the meaningful XOR bits, or
///   1 + the raw 64 (or 32) value bits,
/// choosing whichever is smaller. Repeated values cost a single 0 bit.

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

    /// Append the low `num_bits` of `value`, most-significant bit first.
    /// Byte-at-a-time (up to 9 iterations for 64 bits) rather than the old
    /// bit-at-a-time loop (64); the produced bitstream is identical, and is
    /// pinned by the cross-language fixture test below.
    fn write_bits(&mut self, value: u64, num_bits: usize) {
        let mut remaining = num_bits;
        while remaining > 0 {
            let free = 8 - self.bit_count;
            let take = free.min(remaining);
            let shift = remaining - take;
            let chunk = ((value >> shift) & ((1u64 << take) - 1)) as u8;
            self.current_byte |= chunk << (free - take);
            self.bit_count += take;
            remaining -= take;
            if self.bit_count == 8 {
                self.buffer.push(self.current_byte);
                self.current_byte = 0;
                self.bit_count = 0;
            }
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

    /// Read `num_bits` (MSB first), byte-at-a-time. Mirrors write_bits.
    fn read_bits(&mut self, num_bits: usize) -> Option<u64> {
        let mut value = 0u64;
        let mut remaining = num_bits;
        while remaining > 0 {
            if self.byte_index >= self.data.len() {
                return None;
            }
            let available = 8 - self.bit_index;
            let take = available.min(remaining);
            let byte = self.data[self.byte_index];
            let chunk = (byte >> (available - take)) as u64 & ((1u64 << take) - 1);
            value = (value << take) | chunk;
            self.bit_index += take;
            remaining -= take;
            if self.bit_index == 8 {
                self.byte_index += 1;
                self.bit_index = 0;
            }
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
            prev_delta = self.compress_timestamp(&mut writer, point.timestamp, prev_timestamp, prev_delta)?;
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
            prev_delta = self.compress_timestamp(&mut writer, point.timestamp, prev_timestamp, prev_delta)?;
            self.compress_value_float(&mut writer, point.value, prev_value_bits);

            prev_timestamp = point.timestamp;
            prev_value_bits = point.value.to_bits();
        }

        Ok(writer.finish())
    }

    fn compress_timestamp(
        &self,
        writer: &mut BitWriter,
        timestamp: u64,
        prev_timestamp: u64,
        prev_delta: u64,
    ) -> Result<u64, io::Error> {
        let delta = timestamp - prev_timestamp;

        if delta == self.timestep {
            // Common case: regular timestep
            writer.write_bit(false);
            Ok(prev_delta)
        } else if delta == prev_delta {
            // Delta of deltas is 0
            writer.write_bit(true);
            writer.write_bit(false);
            Ok(prev_delta)
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
            } else if delta_of_deltas >= i32::MIN as i64 && delta_of_deltas <= i32::MAX as i64 {
                writer.write_bits(3, 2); // 32-bit encoding
                writer.write_bits(delta_of_deltas as u64, 32);
            } else {
                // The largest escape is 32 bits; a wider delta-of-deltas would be
                // silently truncated on encode and mis-decoded as a wrong timestamp.
                // The Java encoder enforces the same limit (GorillaCompressor.java) —
                // keep them in lockstep.
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!(
                        "timestamp delta-of-deltas {} exceeds the 32-bit encoding range",
                        delta_of_deltas
                    ),
                ));
            }

            Ok(delta)
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
            // The leading-zeros count must fit the 5-bit field (max 31). A nonzero
            // 64-bit XOR can have up to 63 leading zeros, so clamp and widen the
            // meaningful window accordingly (extra leading bits of the window are
            // zeros of the XOR, so the reconstruction is unchanged).
            let leading_zeros = (xor.leading_zeros() as usize).min(31);
            let trailing_zeros = xor.trailing_zeros() as usize;
            let meaningful_bits = 64 - leading_zeros - trailing_zeros;

            // Compact form costs 5 + 6 + meaningful_bits; the raw fallback costs 64.
            // Use compact whenever it is no larger (meaningful_bits <= 53).
            if meaningful_bits <= 53 {
                writer.write_bit(false);
                writer.write_bits(leading_zeros as u64, 5);
                writer.write_bits(meaningful_bits as u64, 6);
                writer.write_bits(xor >> trailing_zeros, meaningful_bits);
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
            // A nonzero 32-bit XOR has at most 31 leading zeros, so the 5-bit
            // field always fits — no clamp needed here.
            let leading_zeros = xor.leading_zeros() as usize;
            let trailing_zeros = xor.trailing_zeros() as usize;
            let meaningful_bits = 32 - leading_zeros - trailing_zeros;

            // Compact form costs 5 + 6 + meaningful_bits; the raw fallback costs 32.
            // Use compact whenever it is no larger (meaningful_bits <= 21).
            if meaningful_bits <= 21 {
                writer.write_bit(false);
                writer.write_bits(leading_zeros as u64, 5);
                writer.write_bits(meaningful_bits as u64, 6);
                writer.write_bits((xor >> trailing_zeros) as u64, meaningful_bits);
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
                // 32-bit encoding. The encoder wrote the low 32 bits of a signed
                // delta-of-deltas (two's complement), so sign-extend on the way back.
                let value = reader.read_bits(32).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Invalid delta encoding"))?;
                Ok(value as u32 as i32 as i64)
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

    /// Assert a double series round-trips bit-exactly and return the compressed
    /// size in bits per value.
    fn assert_roundtrip_double(series: &[TimeValueDouble]) -> f64 {
        let compressor = GorillaCompressor::new(86400);
        let compressed = compressor.compress_double(series).unwrap();
        let decompressed = compressor.decompress_double(&compressed).unwrap();

        assert_eq!(series.len(), decompressed.len());
        for (original, decompressed) in series.iter().zip(decompressed.iter()) {
            assert_eq!(original.timestamp, decompressed.timestamp);
            assert_eq!(
                original.value.to_bits(),
                decompressed.value.to_bits(),
                "value mismatch at t={}: {} != {}",
                original.timestamp, original.value, decompressed.value
            );
        }
        (compressed.len() * 8) as f64 / series.len() as f64
    }

    fn regular_series(values: &[f64]) -> Vec<TimeValueDouble> {
        values.iter().enumerate()
            .map(|(i, &v)| TimeValueDouble::new(86400 * i as u64, v))
            .collect()
    }

    /// Adjacent values differing only in low mantissa bits have >31 leading zeros
    /// in their XOR. The unclamped encoder truncated the count into the 5-bit
    /// field and corrupted the value on decode.
    #[test]
    fn test_high_leading_zero_xor_roundtrip() {
        let one_ulp_up = f64::from_bits(1.0f64.to_bits() + 1);
        let low_bits_flipped = f64::from_bits(123.456f64.to_bits() ^ 0b111111);
        assert_roundtrip_double(&regular_series(&[1.0, one_ulp_up]));
        assert_roundtrip_double(&regular_series(&[123.456, low_bits_flipped]));
    }

    /// Round operational numbers (1000, 240, 86.4, ...) have sparse mantissas, so
    /// their XORs have few meaningful bits despite the values being far apart.
    /// These must both round-trip and actually compress (the old
    /// `meaningful_bits <= 6` gate pushed them all to the raw 64-bit fallback).
    #[test]
    fn test_round_number_steps_compress() {
        let pattern = [1000.0, 240.0, 86.4, 500.0, 750.0, 120.0, 1000.0, 60.0, 480.0, 240.0];
        let values: Vec<f64> = pattern.iter().cycle().take(2000).cloned().collect();
        let bits_per_value = assert_roundtrip_double(&regular_series(&values));
        assert!(
            bits_per_value < 40.0,
            "round-number steps should compress well below raw 64 bits/value, got {:.1}",
            bits_per_value
        );
    }

    /// Deterministic pseudo-random walk: full-precision doubles exercising every
    /// XOR shape (splitmix64 PRNG, no external dependency).
    #[test]
    fn test_random_walk_roundtrip() {
        let mut state = 0x9E3779B97F4A7C15u64;
        let mut next = move || {
            state = state.wrapping_add(0x9E3779B97F4A7C15);
            let mut z = state;
            z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
            z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
            z ^ (z >> 31)
        };
        let mut value = 100.0f64;
        let mut values = Vec::with_capacity(5000);
        for _ in 0..5000 {
            // Uniform in [-0.5, 0.5), scaled — makes smooth-ish but full-precision data
            let step = (next() >> 11) as f64 / (1u64 << 53) as f64 - 0.5;
            value = (value + step).max(0.0);
            values.push(value);
        }
        assert_roundtrip_double(&regular_series(&values));
    }

    /// NaN, infinities and signed zero must survive bit-exactly.
    #[test]
    fn test_special_values_roundtrip() {
        assert_roundtrip_double(&regular_series(&[
            0.0, f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -0.0, 1.0e300, 5.0e-324,
        ]));
    }

    /// The exact bitstream is pinned: this series and base64 are the same
    /// fixture asserted by the Java side (GorillaCompressorTest.java), so any
    /// change to the encoding on either side fails one of the two suites.
    #[test]
    fn test_bitstream_fixture_matches_java() {
        use base64::{Engine, engine::general_purpose::STANDARD};

        let timestamps: [u64; 12] = [0, 86400, 172800, 259200, 345600, 432000,
            518400, 604800, 1604800, 1691200, 1777600, 1864000];
        let value_bits: [u64; 12] = [
            0x408f400000000000, 0x408f400000000000, 0x406e000000000000,
            0x405599999999999a, 0x405599999999999b, 0x3fbf9add3746f62e,
            0x7ff8000000000000, 0x7ff0000000000000, 0x8000000000000000,
            0x4045000000000000, 0x4045000000000000, 0x407f400000000000,
        ];
        let series: Vec<TimeValueDouble> = timestamps.iter().zip(value_bits.iter())
            .map(|(&t, &b)| TimeValueDouble::new(t, f64::from_bits(b)))
            .collect();

        let compressed = GorillaCompressor::new(86400).compress_double(&series).unwrap();
        assert_eq!(
            STANDARD.encode(&compressed),
            "AAAAAAABUYAAAAAMAAAAAAAAAABAj0AAAAAAABIK4VK17mZmZmZmavwgAAAAFn9/Nbpujexc3/4AAAAAAAATAfgAehIEAz/9AQwEUSiOkA==",
            "encoder bitstream diverged from the committed cross-language fixture"
        );
    }

    /// Non-canonical NaN payloads (e.g. 0xFFF8… from `0.0/0.0` on x86) must pass
    /// through the codec bit-exactly: a decoder that canonicalizes NaN mid-stream
    /// desynchronizes the XOR chain and corrupts every subsequent value. Pinned as
    /// a cross-language fixture (same series and base64 asserted by
    /// GorillaCompressorTest.java) alongside the main bitstream fixture.
    #[test]
    fn test_nan_payload_fixture_matches_java() {
        use base64::{Engine, engine::general_purpose::STANDARD};

        let value_bits: [u64; 6] = [
            0x3ff0000000000000, // 1.0
            0xfff8000000000000, // negative quiet NaN (x86 0.0/0.0)
            0x3ff8000000000000, // 1.5 — the value corrupted by a canonicalizing decoder
            0x7ff800000000beef, // quiet NaN with payload bits
            0x4004000000000000, // 2.5
            0x7ff8000000000000, // canonical quiet NaN
        ];
        let series: Vec<TimeValueDouble> = value_bits.iter().enumerate()
            .map(|(i, &b)| TimeValueDouble::new(86400 * i as u64, f64::from_bits(b)))
            .collect();

        let compressed = GorillaCompressor::new(86400).compress_double(&series).unwrap();
        assert_eq!(
            STANDARD.encode(&compressed),
            "AAAAAAABUYAAAAAGAAAAAAAAAAA/8AAAAAAAAEA3ACgBbf/gAAAAAvu9oAIAAAAAAAAhGf/g",
            "encoder bitstream diverged from the committed NaN-payload fixture"
        );

        let decompressed = GorillaCompressor::new(86400)
            .decompress_double(&compressed).unwrap();
        for (original, decompressed) in series.iter().zip(decompressed.iter()) {
            assert_eq!(original.value.to_bits(), decompressed.value.to_bits());
        }
    }

    /// Irregular timestamps whose delta-of-deltas goes negative beyond the 12-bit
    /// range exercise the 32-bit fallback, which the decoder must sign-extend.
    #[test]
    fn test_large_negative_delta_of_deltas() {
        let series = vec![
            TimeValueDouble::new(0, 1.0),
            TimeValueDouble::new(1_000_000, 2.0),   // dod = +913_600 -> 32-bit branch
            TimeValueDouble::new(1_086_400, 3.0),   // dod = -913_600 -> 32-bit branch, negative
            TimeValueDouble::new(1_172_800, 4.0),   // regular step resumes
        ];
        let compressor = GorillaCompressor::new(86400);
        let compressed = compressor.compress_double(&series).unwrap();
        let decompressed = compressor.decompress_double(&compressed).unwrap();
        assert_eq!(series.len(), decompressed.len());
        for (original, decompressed) in series.iter().zip(decompressed.iter()) {
            assert_eq!(original.timestamp, decompressed.timestamp);
            assert_eq!(original.value.to_bits(), decompressed.value.to_bits());
        }
    }

    /// Float variant: same round-trip guarantees.
    #[test]
    fn test_float_varying_roundtrip() {
        let compressor = GorillaCompressor::new(3600);
        let series: Vec<TimeValueFloat> = (0..2000)
            .map(|i| TimeValueFloat::new(3600 * i as u64, 50.0 + 30.0 * ((i as f32) * 0.1).sin()))
            .collect();
        let compressed = compressor.compress_float(&series).unwrap();
        let decompressed = compressor.decompress_float(&compressed).unwrap();
        assert_eq!(series.len(), decompressed.len());
        for (original, decompressed) in series.iter().zip(decompressed.iter()) {
            assert_eq!(original.timestamp, decompressed.timestamp);
            assert_eq!(original.value.to_bits(), decompressed.value.to_bits());
        }
    }
}