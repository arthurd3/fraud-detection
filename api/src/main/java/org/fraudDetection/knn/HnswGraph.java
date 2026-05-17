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
    private static int levelsBase;
    private static int[] offBase, nbrBase;     // offset (bytes) por camada

    public static boolean isValid(File bin, int expectCount) {
        try (RandomAccessFile r = new RandomAccessFile(bin, "r")) {
            if (r.length() < 28) return false;
            byte[] m = new byte[4]; r.readFully(m);
            if (!(m[0]=='R'&&m[1]=='B'&&m[2]=='H'&&m[3]=='1')) return false;
            return r.readInt() == expectCount;          // count
        } catch (IOException e) { return false; }
    }

    public static void mmap(File bin) throws IOException {
        try (FileChannel ch = FileChannel.open(bin.toPath())) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            if (!(m.get(0)=='R'&&m.get(1)=='B'&&m.get(2)=='H'&&m.get(3)=='1'))
                throw new IOException("magic != RBH1");
            count    = m.getInt(4);
            M        = m.getInt(8);
            M0       = m.getInt(12);
            efC      = m.getInt(16);
            entry    = m.getInt(20);
            maxLevel = m.getInt(24);
            levelsBase = 28;
            int p = levelsBase + count;                 // após levels[count] (1B)
            offBase = new int[maxLevel + 1];
            nbrBase = new int[maxLevel + 1];
            for (int k = 0; k <= maxLevel; k++) {
                offBase[k] = p;
                int edges  = m.getInt(p + count * 4);   // off[count]
                nbrBase[k] = p + (count + 1) * 4;
                p = nbrBase[k] + edges * 4;
            }
            g = m;
        }
    }

    public static int level (int node)        { return g.get(levelsBase + node) & 0xFF; }
    public static int nbrLo  (int node, int k) { return g.getInt(offBase[k] + node*4); }
    public static int nbrHi  (int node, int k) { return g.getInt(offBase[k] + node*4 + 4); }
    public static int nbrAt  (int k, int idx)  { return g.getInt(nbrBase[k] + idx*4); }
}
