package org.fraudDetection.knn;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public final class HnswGraph {
    private HnswGraph() {}

    public static MappedByteBuffer g;
    public static int count, M, M0, efC, entry, maxLevel;
    private static int levelsBase, l0OffBase, l0NbrBase;
    private static int[] pk, nodeBase, offBase, nbrBase;   // índices 1..maxLevel

    public static boolean isValid(File bin, int expectCount) {
        try (RandomAccessFile r = new RandomAccessFile(bin, "r")) {
            if (r.length() < 28) return false;
            byte[] m = new byte[4]; r.readFully(m);
            if (!(m[0]=='R'&&m[1]=='B'&&m[2]=='H'&&m[3]=='2')) return false;
            return r.readInt() == expectCount;                 // count
        } catch (IOException e) { return false; }
    }

    public static void mmap(File bin) throws IOException {
        try (FileChannel ch = FileChannel.open(bin.toPath())) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            if (!(m.get(0)=='R'&&m.get(1)=='B'&&m.get(2)=='H'&&m.get(3)=='2'))
                throw new IOException("magic != RBH2");
            count    = m.getInt(4);
            M        = m.getInt(8);
            M0       = m.getInt(12);
            efC      = m.getInt(16);
            entry    = m.getInt(20);
            maxLevel = m.getInt(24);
            levelsBase = 28;

            l0OffBase = levelsBase + count;                    // após levels[count] (1B)
            l0NbrBase = l0OffBase + (count + 1) * 4;
            int e0    = m.getInt(l0OffBase + count * 4);        // off0[count]
            int p     = l0NbrBase + e0 * 3;

            pk       = new int[maxLevel + 1];
            nodeBase = new int[maxLevel + 1];
            offBase  = new int[maxLevel + 1];
            nbrBase  = new int[maxLevel + 1];
            for (int k = 1; k <= maxLevel; k++) {
                int P = m.getInt(p);
                pk[k]       = P;
                nodeBase[k] = p + 4;
                offBase[k]  = nodeBase[k] + P * 3;
                nbrBase[k]  = offBase[k] + (P + 1) * 4;
                int ek      = m.getInt(offBase[k] + P * 4);     // off_k[P]
                p           = nbrBase[k] + ek * 3;
            }
            g = m;
            mC = -1; mK = -1; mJ = -1;
        }
    }

    private static int get24(int pos) {
        return ((g.get(pos) & 0xFF) << 16) | ((g.get(pos+1) & 0xFF) << 8) | (g.get(pos+2) & 0xFF);
    }

    // memo 1-entrada (single-thread): nbrLo e nbrHi do mesmo (node,k) consecutivos
    private static int mC = -1, mK = -1, mJ = -1;
    private static int idxOf(int node, int k) {
        if (node == mC && k == mK) return mJ;
        int lo = 0, hi = pk[k] - 1, base = nodeBase[k], res = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = get24(base + mid * 3);
            if (v == node) { res = mid; break; }
            if (v < node) lo = mid + 1; else hi = mid - 1;
        }
        mC = node; mK = k; mJ = res;
        return res;
    }

    public static int level(int node) { return g.get(levelsBase + node) & 0xFF; }

    public static int nbrLo(int node, int k) {
        if (k == 0) return g.getInt(l0OffBase + node * 4);
        int j = idxOf(node, k);
        return j < 0 ? 0 : g.getInt(offBase[k] + j * 4);
    }
    public static int nbrHi(int node, int k) {
        if (k == 0) return g.getInt(l0OffBase + (node + 1) * 4);
        int j = idxOf(node, k);
        return j < 0 ? 0 : g.getInt(offBase[k] + (j + 1) * 4);
    }
    public static int nbrAt(int k, int idx) {
        return get24((k == 0 ? l0NbrBase : nbrBase[k]) + idx * 3);
    }
}