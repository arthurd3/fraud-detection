package org.fraudDetection.knn;

/**
 * Fixed-capacity k=5 candidate set kept in ascending raw-i16-distance order.
 * Ported near-verbatim from jvmoonshot TopKSortedArray (k=5, int sums; the
 * f32 INV_SCALE peekDist() helper is dropped — EXACT mode compares i16 sums
 * directly). Backward-shift insertion; at k=5 this beats a binary heap.
 */
final class KdTopK {

    static final int MAX_K = 5;

    private final int[] ids = new int[MAX_K];
    private final int[] sums = new int[MAX_K];
    private int size;

    void clear() { size = 0; }

    int size() { return size; }

    int peekSum() { return sums[size - 1]; }

    boolean contains(int id) {
        if (size > 0 && ids[0] == id) return true;
        if (size > 1 && ids[1] == id) return true;
        if (size > 2 && ids[2] == id) return true;
        if (size > 3 && ids[3] == id) return true;
        if (size > 4 && ids[4] == id) return true;
        return false;
    }

    void push(int id, int sum) {
        int pos = size;
        while (pos > 0 && sums[pos - 1] > sum) {
            sums[pos] = sums[pos - 1];
            ids[pos] = ids[pos - 1];
            pos--;
        }
        sums[pos] = sum;
        ids[pos] = id;
        size++;
    }

    void replaceFarthest(int id, int sum) {
        int pos = MAX_K - 1;
        while (pos > 0 && sums[pos - 1] > sum) {
            sums[pos] = sums[pos - 1];
            ids[pos] = ids[pos - 1];
            pos--;
        }
        sums[pos] = sum;
        ids[pos] = id;
    }

    int idAt(int i) { return ids[i]; }
}
