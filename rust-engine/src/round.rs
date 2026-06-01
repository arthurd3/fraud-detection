//! Replicação BIT-EXATA do `java.lang.Math.round` do OpenJDK (half-up COM a
//! correção do bug de double-rounding — NÃO é `floor(x+0.5)` nem `f::round()`
//! half-away). É o invariante com que o `references.kdt` foi assado
//! (`Quantizer.q16` via `KdTreeBuilder`) e com que o parser (`r4`) opera.
//!
//! Fonte: `java.base/share/classes/java/lang/Math.java` (round(double)/round(float)).

/// `Math.round(double)` do OpenJDK → i64. Half-up, com a guarda do shift.
#[inline]
pub fn java_round_f64(a: f64) -> i64 {
    const SIGNIFICAND_WIDTH: i64 = 53;
    const EXP_BIAS: i64 = 1023;
    const EXP_BIT_MASK: u64 = 0x7FF0_0000_0000_0000;
    const SIGNIF_BIT_MASK: u64 = 0x000F_FFFF_FFFF_FFFF;

    let bits = a.to_bits();
    let biased_exp = ((bits & EXP_BIT_MASK) >> (SIGNIFICAND_WIDTH - 1)) as i64;
    let shift = (SIGNIFICAND_WIDTH - 2 + EXP_BIAS) - biased_exp;
    if (shift & -64) == 0 {
        // a é finito com pow(2,-64) <= ulp(a) < 1
        let mut r = ((bits & SIGNIF_BIT_MASK) | (SIGNIF_BIT_MASK + 1)) as i64;
        if (bits as i64) < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        // |a| >= 2^52 ou ~0 → (long) a (trunca p/ zero; saturação == Java p/ nosso domínio)
        a as i64
    }
}

/// `Math.round(float)` do OpenJDK → i32. Half-up, com a guarda do shift.
#[inline]
pub fn java_round_f32(a: f32) -> i32 {
    const SIGNIFICAND_WIDTH: i32 = 24;
    const EXP_BIAS: i32 = 127;
    const EXP_BIT_MASK: u32 = 0x7F80_0000;
    const SIGNIF_BIT_MASK: u32 = 0x007F_FFFF;

    let bits = a.to_bits();
    let biased_exp = ((bits & EXP_BIT_MASK) >> (SIGNIFICAND_WIDTH - 1)) as i32;
    let shift = (SIGNIFICAND_WIDTH - 2 + EXP_BIAS) - biased_exp;
    if (shift & -32) == 0 {
        let mut r = ((bits & SIGNIF_BIT_MASK) | (SIGNIF_BIT_MASK + 1)) as i32;
        if (bits as i32) < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_f64_matches_java() {
        // valores conhecidos (Java Math.round(double)):
        assert_eq!(java_round_f64(0.0), 0);
        assert_eq!(java_round_f64(0.5), 1); // half-up
        assert_eq!(java_round_f64(1.5), 2);
        assert_eq!(java_round_f64(2.5), 3); // half-up (≠ banker's)
        assert_eq!(java_round_f64(-0.5), 0); // floor(-0.5+0.5)=0; Java round(-0.5)=0
        assert_eq!(java_round_f64(-1.5), -1); // Java round(-1.5) = -1 (half-up toward +inf)
        assert_eq!(java_round_f64(-2.5), -2);
        assert_eq!(java_round_f64(2508.13 * 10000.0), 25081300);
        assert_eq!(java_round_f64(-1.0 * 10000.0), -10000); // sentinela
        assert_eq!(java_round_f64(0.66773 * 10000.0), 6677); // 6677.3 → 6677
        assert_eq!(java_round_f64(0.123449999 * 10000.0), 1234);
    }

    #[test]
    fn round_f32_matches_java() {
        assert_eq!(java_round_f32(0.0), 0);
        assert_eq!(java_round_f32(0.5), 1);
        assert_eq!(java_round_f32(2.5), 3);
        assert_eq!(java_round_f32(-1.0 * 10000.0), -10000);
        assert_eq!(java_round_f32(1.0 * 10000.0), 10000);
        // q16 nunca recebe negativo exceto -1 (clampado); domínio [0,10000].
        assert_eq!(java_round_f32(0.15 * 10000.0), 1500);
    }
}
