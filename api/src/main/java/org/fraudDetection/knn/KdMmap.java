package org.fraudDetection.knn;

import java.nio.MappedByteBuffer;

/**
 * Best-effort mmap hints for the EXACT KD-tree. madvise(HUGEPAGE/RANDOM) is a
 * NO-OP here: jvmoonshot used the Java 25 FFM downcall, but this project is
 * Java 21 (GraalVM CE 21) where finalized FFM is unavailable and the native
 * link of foreign downcalls breaks the Onda 5 native build (same class of
 * failure that forced the Vector API removal in DistanceFunctions). Correctness
 * MUST NOT depend on madvise, so it is intentionally a NO-OP.
 *
 * <p>{@link #prewarm} touches one byte per page over the mapped region using
 * only {@link MappedByteBuffer} (portable, zero native, zero reflection) — a
 * pure read used to fault pages in at boot, never on the request hot path.
 */
public final class KdMmap {

    private static final int PAGE_BYTES = 4096;

    private KdMmap() {}

    /** NO-OP madvise hint (see class doc). Returns false (not applied). */
    public static boolean madviseBestEffort(MappedByteBuffer region, int advice) {
        return false;
    }

    /** Touch one byte per 4 KB page; returns a DCE-defeat XOR. */
    public static int prewarm(MappedByteBuffer region) {
        if (region == null) return 0;
        int end = region.limit();
        int sink = 0;
        for (int i = 0; i < end; i += PAGE_BYTES) sink ^= region.get(i);
        return sink;
    }

    /**
     * Onda 12 Phase D — best-effort MADV_WILLNEED + force-load of the entire
     * mapped region via the standard {@link MappedByteBuffer#load()} call.
     *
     * <p>The companion {@link #prewarm} only faults pages in once; under the
     * 159 MB cgroup memory limit (and the 163 MB references.kdt) the kernel
     * may evict pages of the dataset to reclaim memory for other containers
     * on the host. {@code load()} issues madvise(MADV_WILLNEED) which both
     * (a) reads the mapping into physical memory and (b) hints the page
     * cache to keep those pages resident. Costs ≈ 0.3 s at boot (sequential
     * I/O over 163 MB), nothing on the hot path. /ready does not return 200
     * until after this completes, so k6 starts against a warm cache.
     *
     * <p>Standard NIO API (Java 21+), no FFM/Unsafe — safe under GraalVM
     * native-image.
     */
    public static void loadIntoMemory(MappedByteBuffer region) {
        if (region == null) return;
        region.load();
    }
}
