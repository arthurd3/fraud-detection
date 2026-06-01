//! Leitor do índice `references.kdt` (formato RKD6, tudo little-endian),
//! espelhando `KdTreeIO.loadMmap` + os accessors de `KdTree.java`. O Rust é dono
//! do mmap: read-only sobre o arquivo inteiro, 4 seções como views (zero-copy):
//!
//! ```text
//!   header : "RKD6"(4) + ver i32(=6) + n i32 + dims i32(=14) + stride i32(=19) + root i32(=0)
//!   pts    : short[n*19]            (14 dims permutadas + leftAndDim + right + fraud)
//!   origId : uint24[n] LE
//!   meta   : topNodeCount i32
//!   topBbox: short[topNodeCount*32]
//!   topSlot: int32[n]
//! ```

use crate::layout::*;
use std::ffi::CString;

pub struct Index {
    base: *const u8,
    map_len: usize,
    pub n: usize,
    pub top_node_count: usize,
    pts_off: usize,
    orig_off: usize,
    topbbox_off: usize,
    topslot_off: usize,
}

impl Index {
    /// mmap + valida header RKD6. `do_mlock` = pina a região `pts` (Onda 32).
    pub fn open(path: &str, do_mlock: bool) -> Result<Index, String> {
        let (base, map_len) = unsafe { mmap_file(path)? };
        if map_len < 24 {
            return Err(format!("arquivo curto demais: {} bytes", map_len));
        }
        unsafe {
            // header
            let magic = [
                *base, *base.add(1), *base.add(2), *base.add(3),
            ];
            if &magic != b"RKD6" {
                return Err(format!("magic inválido (esperado RKD6): {:?}", magic));
            }
            let rd_i32 = |off: usize| (base.add(off) as *const i32).read_unaligned();
            let ver = rd_i32(4);
            let n = rd_i32(8);
            let dims = rd_i32(12);
            let stride = rd_i32(16);
            let root = rd_i32(20);
            if ver != 6 || dims != DIMS as i32 || stride != STRIDE as i32 || root != 0 {
                return Err(format!(
                    "ver/dims/stride/root inesperado: {}/{}/{}/{}",
                    ver, dims, stride, root
                ));
            }
            let n = n as usize;
            let pts_off = 24usize;
            let pts_len = n * STRIDE * 2;
            let orig_off = pts_off + pts_len;
            let orig_len = n * 3;
            let meta_off = orig_off + orig_len;
            if meta_off + 4 > map_len {
                return Err("arquivo truncado (meta)".into());
            }
            let top_node_count = (base.add(meta_off) as *const i32).read_unaligned() as usize;
            let topbbox_off = meta_off + 4;
            let topbbox_len = top_node_count * STRIDE_BBOX * 2;
            let topslot_off = topbbox_off + topbbox_len;
            let topslot_len = n * 4;
            if topslot_off + topslot_len > map_len {
                return Err(format!(
                    "arquivo truncado: precisa {} bytes, tem {}",
                    topslot_off + topslot_len,
                    map_len
                ));
            }

            // best-effort: madvise WILLNEED + mlock(pts) (Onda 32 — só pts, ~108 MiB;
            // origId fica reclaimável pela margem do cap de 167 MiB).
            libc::madvise(
                base.add(pts_off) as *mut libc::c_void,
                pts_len,
                libc::MADV_WILLNEED,
            );
            if do_mlock {
                let lim = libc::rlimit {
                    rlim_cur: libc::RLIM_INFINITY,
                    rlim_max: libc::RLIM_INFINITY,
                };
                libc::setrlimit(libc::RLIMIT_MEMLOCK, &lim); // best-effort
                let rc = libc::mlock(base.add(pts_off) as *const libc::c_void, pts_len);
                if rc != 0 {
                    eprintln!(
                        "fd_init: mlock(pts) falhou rc={} (seguindo)",
                        *libc::__errno_location()
                    );
                }
            }

            Ok(Index {
                base,
                map_len,
                n,
                top_node_count,
                pts_off,
                orig_off,
                topbbox_off,
                topslot_off,
            })
        }
    }

    // ── accessors (raw, single-thread hot path; treeIdx in [0,n) garantido pela árvore) ──

    /// i16 da dim permutada `d` (0..18) do nó `tree_idx`.
    #[inline]
    pub fn pt_i16(&self, tree_idx: i32, d: usize) -> i16 {
        unsafe {
            let off = self.pts_off + (tree_idx as usize * STRIDE + d) * 2;
            (self.base.add(off) as *const i16).read_unaligned()
        }
    }

    /// Ponteiro p/ os 16 i16 contíguos do nó (lanes 0..13 dims, 14,15 leftAndDim) — p/ o kernel AVX2.
    #[inline]
    pub fn node_ptr(&self, tree_idx: i32) -> *const i16 {
        unsafe { self.base.add(self.pts_off + tree_idx as usize * STRIDE * 2) as *const i16 }
    }

    #[inline]
    pub fn left_and_dim(&self, tree_idx: i32) -> i32 {
        unsafe {
            let off = self.pts_off + (tree_idx as usize * STRIDE + LANE_LEFT_DIM) * 2;
            (self.base.add(off) as *const i32).read_unaligned()
        }
    }

    #[inline]
    pub fn right(&self, tree_idx: i32) -> i32 {
        unsafe {
            let off = self.pts_off + (tree_idx as usize * STRIDE + LANE_RIGHT) * 2;
            (self.base.add(off) as *const i32).read_unaligned()
        }
    }

    #[inline]
    pub fn fraud_bit(&self, tree_idx: i32) -> i32 {
        (self.pt_i16(tree_idx, LANE_FRAUD) & 1) as i32
    }

    #[inline]
    pub fn orig_id(&self, tree_idx: i32) -> i32 {
        unsafe {
            let o = self.orig_off + tree_idx as usize * 3;
            (*self.base.add(o) as i32)
                | ((*self.base.add(o + 1) as i32) << 8)
                | ((*self.base.add(o + 2) as i32) << 16)
        }
    }

    #[inline]
    pub fn top_slot(&self, tree_idx: i32) -> i32 {
        unsafe {
            let off = self.topslot_off + tree_idx as usize * 4;
            (self.base.add(off) as *const i32).read_unaligned()
        }
    }

    #[inline]
    pub fn top_bbox(&self, slot: i32, k: usize) -> i16 {
        unsafe {
            let off = self.topbbox_off + (slot as usize * STRIDE_BBOX + k) * 2;
            (self.base.add(off) as *const i16).read_unaligned()
        }
    }

    /// Só p/ diagnóstico: tamanho total mapeado.
    pub fn map_len(&self) -> usize {
        self.map_len
    }
}

unsafe fn mmap_file(path: &str) -> Result<(*const u8, usize), String> {
    let cpath = CString::new(path).map_err(|_| "path com NUL".to_string())?;
    let fd = libc::open(cpath.as_ptr(), libc::O_RDONLY);
    if fd < 0 {
        return Err(format!("open({}) falhou errno={}", path, *libc::__errno_location()));
    }
    let mut st: libc::stat = std::mem::zeroed();
    if libc::fstat(fd, &mut st) != 0 {
        let e = *libc::__errno_location();
        libc::close(fd);
        return Err(format!("fstat falhou errno={}", e));
    }
    let len = st.st_size as usize;
    let p = libc::mmap(
        std::ptr::null_mut(),
        len,
        libc::PROT_READ,
        libc::MAP_PRIVATE,
        fd,
        0,
    );
    libc::close(fd);
    if p == libc::MAP_FAILED {
        return Err(format!("mmap falhou errno={}", *libc::__errno_location()));
    }
    Ok((p as *const u8, len))
}
