//! Constantes de geometria + encode/decode de nav, espelhando `KdLayout.java`,
//! `KdTree.java` e `KdScratch.java`. RKD6: STRIDE=19.

pub const DIMS: usize = 14;
pub const STRIDE: usize = 19; // 14 dims + leftAndDim[14:15] + right[16:17] + fraud[18]
pub const STRIDE_BBOX: usize = 32; // 16 lo + 16 hi shorts por nó top-bbox

pub const LANE_LEFT_DIM: usize = 14;
pub const LANE_RIGHT: usize = 16;
pub const LANE_FRAUD: usize = 18;

pub const TOP_BBOX_DEPTH: i32 = 18;
pub const BBF_MAX_DEPTH: i32 = 22;

pub const MAX_K: usize = 5;
pub const BBF_HEAP_CAP: usize = 1024;
pub const BBF_POOL_CAP: usize = 1024;
pub const POOL_CAP: usize = 1024;

/// permuted[d] = semantic[DIM_PERMUTATION[d]]. Idêntico ao jvmoonshot/KdLayout.
pub const DIM_PERMUTATION: [usize; DIMS] = [6, 10, 9, 5, 11, 2, 4, 7, 0, 1, 3, 8, 12, 13];

/// INV_PERMUTATION[s] = d tal que DIM_PERMUTATION[d] == s.
pub fn inv_permutation() -> [usize; DIMS] {
    let mut inv = [0usize; DIMS];
    let mut d = 0;
    while d < DIMS {
        inv[DIM_PERMUTATION[d]] = d;
        d += 1;
    }
    inv
}

/// Sign-extend do índice esquerdo de 28 bits (preserva o sentinela -1 de filho ausente).
#[inline]
pub fn unpack_left(left_and_dim: i32) -> i32 {
    (left_and_dim << 4) >> 4
}

/// Dim de split (4 bits altos), logical shift.
#[inline]
pub fn unpack_dim(left_and_dim: i32) -> usize {
    (((left_and_dim as u32) >> 28) & 0xF) as usize
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inv_perm_roundtrip() {
        let inv = inv_permutation();
        for d in 0..DIMS {
            assert_eq!(inv[DIM_PERMUTATION[d]], d);
        }
        // INV esperado (derivado): para s=0 → d=8 (DIM_PERMUTATION[8]=0), etc.
        assert_eq!(inv[6], 0);
        assert_eq!(inv[0], 8);
        assert_eq!(inv[13], 13);
    }

    #[test]
    fn pack_unpack() {
        // pack(left, dim) = (left & 0x0FFFFFFF) | ((dim & 0xF) << 28)
        let pack = |left: i32, dim: i32| (left & 0x0FFF_FFFF) | ((dim & 0xF) << 28);
        for &(l, d) in &[(0, 0), (1234567, 13), (0x0FFF_FFFF, 7), (-1, 5)] {
            let p = pack(l, d);
            assert_eq!(unpack_dim(p), d as usize);
            // unpack_left sign-extends 28 bits: -1 (todos 1 nos 28 bits baixos) volta -1.
            let expect_left = (l << 4) >> 4;
            assert_eq!(unpack_left(p), expect_left);
        }
        // sentinela -1 (filho ausente): left=-1 → 0x0FFFFFFF nos 28 bits → unpack -1
        let p = pack(-1, 0);
        assert_eq!(unpack_left(p), -1);
    }
}
