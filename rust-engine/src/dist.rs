//! Kernel de distância i16 (Σ sobre 14 dims de (q[d]-node[d])², acumulador i32 —
//! invariante do contest: soma < 2^31). Escalar (referência/fallback) + AVX2
//! explícito (`_mm256_madd_epi16`). Em prod (`-C target-cpu=x86-64-v3`) o AVX2 é
//! escolhido em COMPILE-TIME (`cfg!`, branch elidido); o dev box (sem v3) usa
//! runtime-detect + escalar (mantém `cargo test`/`agree` portáveis).
//!
//! O escalar e o AVX2 dão o MESMO i32 (soma de inteiros é associativa e não há
//! overflow sob o invariante) → E=0 independe de qual caminho roda.

use crate::layout::DIMS;
use std::sync::atomic::{AtomicU8, Ordering};

static AVX2: AtomicU8 = AtomicU8::new(0); // 0=desconhecido, 1=sim, 2=não

/// Resolve o suporte a AVX2 uma vez (chamado no `fd_init`/`init_from_path`).
pub fn detect_cpu() {
    let v = if std::is_x86_feature_detected!("avx2") { 1 } else { 2 };
    AVX2.store(v, Ordering::Relaxed);
}

/// Σ_{d=0..13} (q[d]-node[d])². `q` e `node` apontam p/ ≥16 i16 contíguos
/// (lanes 0..13 = dims permutadas; 14,15 ignoradas — no nó são leftAndDim).
#[inline]
pub fn dist_sum_i16(q: *const i16, node: *const i16) -> i32 {
    // Prod compila com `-C target-cpu=x86-64-v3` ⇒ `cfg!(target_feature="avx2")` é
    // const-TRUE ⇒ o compilador elide o branch + a leitura do atomic e inlina
    // `dist_avx2` direto no hot loop (~300 chamadas/query). Sem v3 (cargo test/agree
    // no dev box) cai no runtime-detect + escalar → portável.
    if cfg!(target_feature = "avx2") {
        unsafe { dist_avx2(q, node) }
    } else if AVX2.load(Ordering::Relaxed) == 1 {
        unsafe { dist_avx2(q, node) }
    } else {
        dist_scalar(q, node)
    }
}

#[inline]
pub fn dist_scalar(q: *const i16, node: *const i16) -> i32 {
    // i32 com wrap (== Java `int` e == os intrínsecos SSE/AVX, que wrapam mod 2^32).
    // O invariante do contest mantém a soma < 2^31 em dados reais (sem wrap); o wrap
    // explícito só evita o panic de overflow do debug em inputs sintéticos do teste.
    let mut sum: i32 = 0;
    unsafe {
        let mut d = 0;
        while d < DIMS {
            let diff = (*q.add(d) as i32) - (*node.add(d) as i32);
            sum = sum.wrapping_add(diff.wrapping_mul(diff));
            d += 1;
        }
    }
    sum
}

#[target_feature(enable = "avx2")]
unsafe fn dist_avx2(q: *const i16, node: *const i16) -> i32 {
    use std::arch::x86_64::*;
    let vq = _mm256_loadu_si256(q as *const __m256i); // 16 i16 (lanes 14,15 = 0)
    let vn = _mm256_loadu_si256(node as *const __m256i); // 16 i16 (lanes 14,15 = leftAndDim)
    let diff = _mm256_sub_epi16(vq, vn);
    // máscara: lanes 0..13 = 0xFFFF; lanes 14,15 (as duas mais altas) = 0.
    let mask = _mm256_set_epi16(
        0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    );
    let dm = _mm256_and_si256(diff, mask);
    let prod = _mm256_madd_epi16(dm, dm); // 8 i32: (dm[2i])²+(dm[2i+1])²
    // soma horizontal dos 8 i32
    let lo = _mm256_castsi256_si128(prod);
    let hi = _mm256_extracti128_si256::<1>(prod);
    let s4 = _mm_add_epi32(lo, hi); // 4 i32
    let s2 = _mm_add_epi32(s4, _mm_shuffle_epi32::<0x4E>(s4)); // [a+c,b+d,..]
    let s1 = _mm_add_epi32(s2, _mm_shuffle_epi32::<0xB1>(s2)); // lane0 = total
    _mm_cvtsi128_si32(s1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn avx2_equals_scalar() {
        detect_cpu();
        if AVX2.load(Ordering::Relaxed) != 1 {
            eprintln!("dev box sem AVX2 — pulando avx2_equals_scalar");
            return;
        }
        // várias queries/nós pseudo-aleatórios (LCG determinístico, sem rand dep)
        let mut seed: u64 = 0x1234_5678_9abc_def0;
        let mut next = || {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            ((seed >> 33) as i32 % 20001 - 10000) as i16 // [-10000,10000]
        };
        for _ in 0..100_000 {
            let mut q = [0i16; 16];
            let mut node = [0i16; 16];
            for d in 0..14 {
                q[d] = next();
                node[d] = next();
            }
            // lanes 14,15: query 0 (como o scratch), nó = "leftAndDim" garbage
            node[14] = next();
            node[15] = next();
            let a = dist_scalar(q.as_ptr(), node.as_ptr());
            let b = unsafe { dist_avx2(q.as_ptr(), node.as_ptr()) };
            assert_eq!(a, b, "avx2 != scalar p/ q={:?} node={:?}", q, node);
        }
    }
}
