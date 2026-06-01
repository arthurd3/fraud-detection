//! Motor de busca k-NN EXATO: KD-tree i16 + branch-and-bound (BBF) + rerank
//! double. Port byte-a-byte de `KdTree.java` (Onda 7 v2 + Ondas 11/22).
//! MODO EXATO: sem MAX_VISITS/epsilon/relax (produção nunca seta KDTREE_MAX_VISITS).
//!
//! Garantia E=0: a árvore i16 acha o top-5 i16 exato; o pool é um superset
//! provado do top-5 double do C; o rerank double replica `main.c knn_classify`
//! (origId ascendente, strict `<` + break ⇒ menor índice original vence empate).

use crate::dist::dist_sum_i16;
use crate::io::Index;
use crate::layout::*;
use crate::quant::q16;
use crate::round::java_round_f64;

/// Top-K fixo (k=5) em ordem ascendente de soma i16 crua. Espelha `KdTopK.java`.
pub struct TopK {
    ids: [i32; MAX_K],
    sums: [i32; MAX_K],
    size: usize,
}

impl TopK {
    fn new() -> TopK {
        TopK { ids: [0; MAX_K], sums: [0; MAX_K], size: 0 }
    }
    #[inline]
    fn clear(&mut self) {
        self.size = 0;
    }
    #[inline]
    fn size(&self) -> usize {
        self.size
    }
    #[inline]
    fn peek_sum(&self) -> i32 {
        self.sums[self.size - 1]
    }
    #[inline]
    fn contains(&self, id: i32) -> bool {
        let mut i = 0;
        while i < self.size {
            if self.ids[i] == id {
                return true;
            }
            i += 1;
        }
        false
    }
    #[inline]
    fn push(&mut self, id: i32, sum: i32) {
        let mut pos = self.size;
        while pos > 0 && self.sums[pos - 1] > sum {
            self.sums[pos] = self.sums[pos - 1];
            self.ids[pos] = self.ids[pos - 1];
            pos -= 1;
        }
        self.sums[pos] = sum;
        self.ids[pos] = id;
        self.size += 1;
    }
    #[inline]
    fn replace_farthest(&mut self, id: i32, sum: i32) {
        let mut pos = MAX_K - 1;
        while pos > 0 && self.sums[pos - 1] > sum {
            self.sums[pos] = self.sums[pos - 1];
            self.ids[pos] = self.ids[pos - 1];
            pos -= 1;
        }
        self.sums[pos] = sum;
        self.ids[pos] = id;
    }
}

/// Scratch single-thread pré-alocada (zero-alloc por query). Espelha `KdScratch.java`.
pub struct Scratch {
    results: TopK,
    slab: [i32; DIMS],
    permuted_query_i16: [i16; STRIDE], // lanes 0..13 = q16(perm); 14..18 = 0
    query_round4: [f64; DIMS],
    // BBF heap (4 arrays paralelos) + pool de slabs
    bbf_tree_idx: Vec<i32>,
    bbf_slab_sum: Vec<i32>,
    bbf_depth: Vec<i32>,
    bbf_slab_idx: Vec<usize>, // índice no bbf_slab_pool (0..BBF_POOL_CAP)
    bbf_slab_pool: Vec<i32>,
    bbf_size: usize,
    bbf_slab_next: usize,
    // pool de candidatos (3 arrays paralelos)
    pool_tree_idx: Vec<i32>,
    pool_orig: Vec<i32>,
    pool_i16_sum: Vec<i32>,
    pool_size: usize,
    // rerank
    ref_semantic: [i16; DIMS],
    rr_dist: [f64; MAX_K],
    rr_idx: [i32; MAX_K],
    peek_sum_final_i16: i32,
    inv_perm: [usize; DIMS],
}

impl Scratch {
    pub fn new() -> Box<Scratch> {
        Box::new(Scratch {
            results: TopK::new(),
            slab: [0; DIMS],
            permuted_query_i16: [0; STRIDE],
            query_round4: [0.0; DIMS],
            bbf_tree_idx: vec![0; BBF_HEAP_CAP],
            bbf_slab_sum: vec![0; BBF_HEAP_CAP],
            bbf_depth: vec![0; BBF_HEAP_CAP],
            bbf_slab_idx: vec![0; BBF_HEAP_CAP],
            bbf_slab_pool: vec![0; BBF_POOL_CAP * DIMS],
            bbf_size: 0,
            bbf_slab_next: 0,
            pool_tree_idx: vec![0; POOL_CAP],
            pool_orig: vec![0; POOL_CAP],
            pool_i16_sum: vec![0; POOL_CAP],
            pool_size: 0,
            ref_semantic: [0; DIMS],
            rr_dist: [0.0; MAX_K],
            rr_idx: [-1; MAX_K],
            peek_sum_final_i16: 0,
            inv_perm: inv_permutation(),
        })
    }

    // ── prepareSearch ────────────────────────────────────────────────────────────
    fn prepare(&mut self, q_sem: &[f32; DIMS]) {
        self.results.clear();
        for d in 0..DIMS {
            self.slab[d] = 0;
        }
        self.bbf_size = 0;
        self.bbf_slab_next = 0;
        self.pool_size = 0;
        self.peek_sum_final_i16 = 0;
        // permuted query i16 (lanes 0..13); 14..18 = 0 (p/ o kernel AVX2 mascarar)
        for d in 0..DIMS {
            self.permuted_query_i16[d] = q16(q_sem[DIM_PERMUTATION[d]]);
        }
        for d in DIMS..STRIDE {
            self.permuted_query_i16[d] = 0;
        }
        // round4(query) em ordem SEMÂNTICA (double), pré-computado 1×/query (H1)
        for d in 0..DIMS {
            self.query_round4[d] = java_round_f64((q_sem[d] as f64) * 10000.0) as f64 / 10000.0;
        }
    }

    // ── poolRecord ───────────────────────────────────────────────────────────────
    #[inline]
    fn pool_record(&mut self, index: &Index, tree_idx: i32, dist: i32) {
        if self.results.size() < MAX_K || dist <= self.results.peek_sum() {
            if self.pool_size < POOL_CAP {
                self.pool_tree_idx[self.pool_size] = tree_idx;
                self.pool_orig[self.pool_size] = index.orig_id(tree_idx);
                self.pool_i16_sum[self.pool_size] = dist;
                self.pool_size += 1;
            }
        }
    }

    // ── considerNode (prime/descend) ───────────────────────────────────────────────
    #[inline]
    fn consider_node(&mut self, index: &Index, tree_idx: i32, dist: i32, full: bool) {
        if !full {
            if !self.results.contains(tree_idx) {
                self.results.push(tree_idx, dist);
            }
        } else if dist < self.results.peek_sum() && !self.results.contains(tree_idx) {
            self.results.replace_farthest(tree_idx, dist);
        }
        self.pool_record(index, tree_idx, dist);
    }

    // ── prime (beam-of-2 greedy descent ×2) ────────────────────────────────────────
    fn prime(&mut self, index: &Index, q: *const i16, k: usize) {
        let mut best_far: i32 = -1;
        let mut best_far_delta_sq: i32 = i32::MAX;
        let mut tree_idx: i32 = 0;

        // Descent #1 — segue near; lembra o melhor far.
        while tree_idx >= 0 {
            let dist = dist_sum_i16(q, index.node_ptr(tree_idx));
            let full = self.results.size() >= k;
            self.consider_node(index, tree_idx, dist, full);
            let lad = index.left_and_dim(tree_idx);
            let split_dim = unpack_dim(lad);
            let left_idx = unpack_left(lad);
            let right_idx = index.right(tree_idx);
            let delta = self.permuted_query_i16[split_dim] as i32 - index.pt_i16(tree_idx, split_dim) as i32;
            let (near, far) = if delta < 0 { (left_idx, right_idx) } else { (right_idx, left_idx) };
            if far >= 0 {
                let delta_sq = delta * delta;
                if delta_sq < best_far_delta_sq {
                    best_far_delta_sq = delta_sq;
                    best_far = far;
                }
            }
            tree_idx = near;
        }

        // Descent #2 — do melhor far, mesma descida near-only.
        if best_far < 0 {
            return;
        }
        tree_idx = best_far;
        while tree_idx >= 0 {
            let dist = dist_sum_i16(q, index.node_ptr(tree_idx));
            let full = self.results.size() >= k;
            self.consider_node(index, tree_idx, dist, full);
            let lad = index.left_and_dim(tree_idx);
            let split_dim = unpack_dim(lad);
            let left_idx = unpack_left(lad);
            let right_idx = index.right(tree_idx);
            let delta = self.permuted_query_i16[split_dim] as i32 - index.pt_i16(tree_idx, split_dim) as i32;
            tree_idx = if delta < 0 { left_idx } else { right_idx };
        }
    }

    // ── descend (DFS fallback p/ FAR profundos) ─────────────────────────────────────
    fn descend(&mut self, index: &Index, q: *const i16, k: usize, tree_idx: i32, slab_sum: i32, depth: i32) {
        if tree_idx < 0 {
            return;
        }
        if self.results.size() >= k {
            let thresh_sum = self.results.peek_sum();
            if slab_sum > thresh_sum {
                return;
            }
            if depth <= TOP_BBOX_DEPTH {
                let slot = index.top_slot(tree_idx);
                if slot >= 0 && bbox_prunes(index, q, slot, thresh_sum) {
                    return;
                }
            }
        }
        let dist = dist_sum_i16(q, index.node_ptr(tree_idx));
        let full = self.results.size() >= k;
        self.consider_node(index, tree_idx, dist, full);
        let lad = index.left_and_dim(tree_idx);
        let split_dim = unpack_dim(lad);
        let left_idx = unpack_left(lad);
        let right_idx = index.right(tree_idx);
        let delta = self.permuted_query_i16[split_dim] as i32 - index.pt_i16(tree_idx, split_dim) as i32;
        let (near, far) = if delta < 0 { (left_idx, right_idx) } else { (right_idx, left_idx) };

        self.descend(index, q, k, near, slab_sum, depth + 1);

        let old_slab_d = self.slab[split_dim];
        let new_slab_d = delta * delta;
        let new_slab_sum = slab_sum - old_slab_d + new_slab_d;
        if self.results.size() < k || new_slab_sum <= self.results.peek_sum() {
            self.slab[split_dim] = new_slab_d;
            self.descend(index, q, k, far, new_slab_sum, depth + 1);
            self.slab[split_dim] = old_slab_d;
        }
    }

    // ── descendBBF (best-first) ─────────────────────────────────────────────────────
    fn descend_bbf(&mut self, index: &Index, q: *const i16, k: usize) {
        self.continue_dfs_bbf(index, q, 0, 0, 0, k);
        while self.bbf_size > 0 {
            let pop_ti = self.bbf_tree_idx[0];
            let pop_ss = self.bbf_slab_sum[0];
            let pop_de = self.bbf_depth[0];
            let pop_si = self.bbf_slab_idx[0];
            let last_idx = self.bbf_size - 1;
            self.bbf_size = last_idx;
            if last_idx > 0 {
                let ti = self.bbf_tree_idx[last_idx];
                let ss = self.bbf_slab_sum[last_idx];
                let de = self.bbf_depth[last_idx];
                let si = self.bbf_slab_idx[last_idx];
                let mut i = 0usize;
                let half = last_idx >> 1;
                while i < half {
                    let mut child = (i << 1) + 1;
                    let right = child + 1;
                    if right < last_idx && self.bbf_slab_sum[right] < self.bbf_slab_sum[child] {
                        child = right;
                    }
                    if ss <= self.bbf_slab_sum[child] {
                        break;
                    }
                    self.bbf_tree_idx[i] = self.bbf_tree_idx[child];
                    self.bbf_slab_sum[i] = self.bbf_slab_sum[child];
                    self.bbf_depth[i] = self.bbf_depth[child];
                    self.bbf_slab_idx[i] = self.bbf_slab_idx[child];
                    i = child;
                }
                self.bbf_tree_idx[i] = ti;
                self.bbf_slab_sum[i] = ss;
                self.bbf_depth[i] = de;
                self.bbf_slab_idx[i] = si;
            }
            if self.results.size() >= k && pop_ss > self.results.peek_sum() {
                continue;
            }
            // restaura slab do pool: bbfSlabPool[pop_si*DIMS .. +DIMS] → slab
            let base = pop_si * DIMS;
            for j in 0..DIMS {
                self.slab[j] = self.bbf_slab_pool[base + j];
            }
            self.continue_dfs_bbf(index, q, pop_ti, pop_ss, pop_de, k);
        }
    }

    fn continue_dfs_bbf(&mut self, index: &Index, q: *const i16, mut tree_idx: i32, slab_sum: i32, mut depth: i32, k: usize) {
        while tree_idx >= 0 {
            let mut full = self.results.size() >= k;
            let mut threshold_sum = if full { self.results.peek_sum() } else { i32::MAX };
            if full {
                if slab_sum > threshold_sum {
                    return;
                }
                if depth <= TOP_BBOX_DEPTH {
                    let slot = index.top_slot(tree_idx);
                    if slot >= 0 && bbox_prunes(index, q, slot, threshold_sum) {
                        return;
                    }
                }
            }
            // MODO EXATO: sem MAX_VISITS (produção nunca seta KDTREE_MAX_VISITS).
            let dist = dist_sum_i16(q, index.node_ptr(tree_idx));
            if !full {
                if !self.results.contains(tree_idx) {
                    self.results.push(tree_idx, dist);
                    full = self.results.size() >= k;
                    if full {
                        threshold_sum = self.results.peek_sum();
                    }
                }
                self.pool_record(index, tree_idx, dist);
            } else if dist < self.results.peek_sum() && !self.results.contains(tree_idx) {
                self.results.replace_farthest(tree_idx, dist);
                threshold_sum = self.results.peek_sum();
                self.pool_record(index, tree_idx, dist);
            } else {
                self.pool_record(index, tree_idx, dist);
            }
            let lad = index.left_and_dim(tree_idx);
            let split_dim = unpack_dim(lad);
            let left_idx = unpack_left(lad);
            let right_idx = index.right(tree_idx);
            let delta = self.permuted_query_i16[split_dim] as i32 - index.pt_i16(tree_idx, split_dim) as i32;
            let (near, far) = if delta < 0 { (left_idx, right_idx) } else { (right_idx, left_idx) };

            if far >= 0 {
                let old_slab_d = self.slab[split_dim];
                let new_slab_d = delta * delta;
                let new_slab_sum = slab_sum - old_slab_d + new_slab_d;
                if !full || new_slab_sum <= threshold_sum {
                    let next_depth = depth + 1;
                    if next_depth <= BBF_MAX_DEPTH
                        && self.bbf_size < BBF_HEAP_CAP
                        && self.bbf_slab_next < BBF_POOL_CAP
                    {
                        let new_slab_idx = self.bbf_slab_next;
                        self.bbf_slab_next += 1;
                        let pool_off = new_slab_idx * DIMS;
                        for j in 0..DIMS {
                            self.bbf_slab_pool[pool_off + j] = self.slab[j];
                        }
                        self.bbf_slab_pool[pool_off + split_dim] = new_slab_d;
                        let mut i = self.bbf_size;
                        self.bbf_size += 1;
                        while i > 0 {
                            let parent = (i - 1) >> 1;
                            if self.bbf_slab_sum[parent] <= new_slab_sum {
                                break;
                            }
                            self.bbf_tree_idx[i] = self.bbf_tree_idx[parent];
                            self.bbf_slab_sum[i] = self.bbf_slab_sum[parent];
                            self.bbf_depth[i] = self.bbf_depth[parent];
                            self.bbf_slab_idx[i] = self.bbf_slab_idx[parent];
                            i = parent;
                        }
                        self.bbf_tree_idx[i] = far;
                        self.bbf_slab_sum[i] = new_slab_sum;
                        self.bbf_depth[i] = next_depth;
                        self.bbf_slab_idx[i] = new_slab_idx;
                    } else {
                        self.slab[split_dim] = new_slab_d;
                        self.descend(index, q, k, far, new_slab_sum, next_depth);
                        self.slab[split_dim] = old_slab_d;
                    }
                }
            }
            tree_idx = near;
            depth += 1;
        }
    }

    // ── search EXATO (== main.c knn_classify) → fraudCount 0..5 ──────────────────────
    pub fn search(&mut self, index: &Index, q_sem: &[f32; DIMS]) -> i32 {
        self.prepare(q_sem);
        let q: *const i16 = self.permuted_query_i16.as_ptr();

        self.prime(index, q, MAX_K);
        self.descend_bbf(index, q, MAX_K);
        self.peek_sum_final_i16 = self.results.peek_sum();

        let pn = self.pool_size;
        // ordena pool (3 arrays) por origId ascendente (heapsort in-place, == Java)
        heapsort_by_orig(
            &mut self.pool_tree_idx,
            &mut self.pool_i16_sum,
            &mut self.pool_orig,
            pn,
        );

        for i in 0..MAX_K {
            self.rr_dist[i] = 1e30;
            self.rr_idx[i] = -1;
        }
        let final_i16 = self.peek_sum_final_i16;
        let mut prev_orig: i32 = -1;
        for p in 0..pn {
            let oid = self.pool_orig[p];
            if oid == prev_orig {
                continue; // dedup
            }
            prev_orig = oid;
            // H2 early-exit: i16Sum > 5º i16 final ⇒ doubleSum > 5º double ⇒ fora do top-5
            if self.pool_i16_sum[p] > final_i16 {
                continue;
            }
            let ti = self.pool_tree_idx[p];
            // dequantiza candidato p/ i16 SEMÂNTICO
            for sdim in 0..DIMS {
                self.ref_semantic[sdim] = index.pt_i16(ti, self.inv_perm[sdim]);
            }
            let d = sq_dist_double_like_c(&self.query_round4, &self.ref_semantic);
            // insertion-sort 5: strict '<' + break (empate → menor origId, ordem asc)
            for j in 0..MAX_K {
                if d < self.rr_dist[j] {
                    let mut kk = MAX_K - 1;
                    while kk > j {
                        self.rr_dist[kk] = self.rr_dist[kk - 1];
                        self.rr_idx[kk] = self.rr_idx[kk - 1];
                        kk -= 1;
                    }
                    self.rr_dist[j] = d;
                    self.rr_idx[j] = oid;
                    break;
                }
            }
        }

        // fraud_n entre os 5 finais (fraud bit via origId → treeIdx no pool ordenado)
        let mut fraud = 0;
        for i in 0..MAX_K {
            let oid = self.rr_idx[i];
            if oid >= 0 {
                let ti = tree_idx_for_orig(&self.pool_tree_idx, &self.pool_orig, pn, oid);
                if ti >= 0 && index.fraud_bit(ti) != 0 {
                    fraud += 1;
                }
            }
        }
        fraud
    }
}

/// bboxPrunesI16Sum: lower-bound i16 do nó top-bbox vs threshold. Espelha Java.
#[inline]
fn bbox_prunes(index: &Index, q: *const i16, slot: i32, threshold_sum: i32) -> bool {
    let mut part_lo: i32 = 0;
    let mut d = 0usize;
    unsafe {
        while d < 8 {
            let qd = *q.add(d) as i32;
            let lo = index.top_bbox(slot, d) as i32;
            let hi = index.top_bbox(slot, 16 + d) as i32;
            let clamped = (lo - qd).max((qd - hi).max(0));
            part_lo += clamped * clamped;
            d += 1;
        }
        if part_lo >= threshold_sum {
            return true;
        }
        let mut part_hi: i32 = 0;
        while d < DIMS {
            let qd = *q.add(d) as i32;
            let lo = index.top_bbox(slot, d) as i32;
            let hi = index.top_bbox(slot, 16 + d) as i32;
            let clamped = (lo - qd).max((qd - hi).max(0));
            part_hi += clamped * clamped;
            d += 1;
        }
        part_lo + part_hi >= threshold_sum
    }
}

/// sqDistDoubleLikeC(double[] queryRound4, short[] refI16Semantic) — IDÊNTICO a
/// main.c euclidean_dist: Σ_{i=0..13} (qR4[i] - refI16[i]/10000.0)² em f64.
/// SEM FMA/contração (Rust f64 é estrito por padrão) ⇒ bit-idêntico ao Java.
#[inline]
fn sq_dist_double_like_c(q_round4: &[f64; DIMS], ref_i16: &[i16; DIMS]) -> f64 {
    let mut sum = 0.0_f64;
    for i in 0..DIMS {
        let a = q_round4[i];
        let b = ref_i16[i] as f64 / 10000.0;
        let diff = a - b;
        sum += diff * diff;
    }
    sum
}

/// Busca binária do treeIdx p/ um origId no pool ordenado ascendente.
#[inline]
fn tree_idx_for_orig(p_tree: &[i32], p_orig: &[i32], pn: usize, oid: i32) -> i32 {
    let mut lo: i32 = 0;
    let mut hi: i32 = pn as i32 - 1;
    while lo <= hi {
        let mid = ((lo + hi) >> 1) as usize;
        let v = p_orig[mid];
        if v == oid {
            return p_tree[mid];
        }
        if v < oid {
            lo = mid as i32 + 1;
        } else {
            hi = mid as i32 - 1;
        }
    }
    -1
}

// ── heapsort de 3 arrays paralelos por `key` (origId) ascendente — == Java ──────────
fn heapsort_by_orig(aux: &mut [i32], sum_aux: &mut [i32], key: &mut [i32], n: usize) {
    if n < 2 {
        return;
    }
    let mut i = (n / 2) as i64 - 1;
    while i >= 0 {
        sift_down(aux, sum_aux, key, i as usize, n);
        i -= 1;
    }
    let mut end = n - 1;
    while end > 0 {
        swap3(aux, sum_aux, key, 0, end);
        sift_down(aux, sum_aux, key, 0, end);
        end -= 1;
    }
}

#[inline]
fn sift_down(aux: &mut [i32], sum_aux: &mut [i32], key: &mut [i32], mut i: usize, n: usize) {
    loop {
        let l = 2 * i + 1;
        let r = l + 1;
        let mut mx = i;
        if l < n && key[l] > key[mx] {
            mx = l;
        }
        if r < n && key[r] > key[mx] {
            mx = r;
        }
        if mx == i {
            return;
        }
        swap3(aux, sum_aux, key, i, mx);
        i = mx;
    }
}

#[inline]
fn swap3(aux: &mut [i32], sum_aux: &mut [i32], key: &mut [i32], x: usize, y: usize) {
    key.swap(x, y);
    aux.swap(x, y);
    sum_aux.swap(x, y);
}
