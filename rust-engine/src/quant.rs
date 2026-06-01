//! Quantização i16 da query, espelhando `Quantizer.java` (math f32).

use crate::round::java_round_f32;

/// `Quantizer.clamp(float)`: trava em [-1, 1] (f32).
#[inline]
pub fn clamp_f32(v: f32) -> f32 {
    if v < -1.0 {
        -1.0
    } else if v > 1.0 {
        1.0
    } else {
        v
    }
}

/// `Quantizer.q16(float v) = (short) Math.round(clamp(v) * 10000f)`.
/// Math f32 (clamp(v)*10000f é multiplicação float); round f32; cast → i16 (low 16 bits).
#[inline]
pub fn q16(v: f32) -> i16 {
    java_round_f32(clamp_f32(v) * 10000.0_f32) as i16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn q16_basic() {
        assert_eq!(q16(0.0), 0);
        assert_eq!(q16(1.0), 10000);
        assert_eq!(q16(-1.0), -10000); // sentinela
        assert_eq!(q16(0.15), 1500);
        assert_eq!(q16(2.0), 10000); // clamp
        assert_eq!(q16(-5.0), -10000); // clamp
    }
}
