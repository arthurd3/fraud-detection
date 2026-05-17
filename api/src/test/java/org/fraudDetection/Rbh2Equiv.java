package org.fraudDetection;

import org.fraudDetection.knn.HnswGraph;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/** Gate 1 — RBH1(golden) ≡ RBH2 (mesmas arestas p/ todos os nós/camadas). */
public final class Rbh2Equiv {
    // ---- leitor RBH1 mínimo (uniforme count+1, nbr int32) ----
    static MappedByteBuffer r1; static int c1, maxL1, lvlBase1;
    static int[] offBase1, nbrBase1;
    static void openRBH1(String path) throws IOException {
        FileChannel ch = FileChannel.open(new File(path).toPath());
        MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        if (!(m.get(0)=='R'&&m.get(1)=='B'&&m.get(2)=='H'&&m.get(3)=='1'))
            throw new IOException("golden não é RBH1");
        c1 = m.getInt(4); maxL1 = m.getInt(24);
        lvlBase1 = 28;
        int p = lvlBase1 + c1;
        offBase1 = new int[maxL1 + 1]; nbrBase1 = new int[maxL1 + 1];
        for (int k = 0; k <= maxL1; k++) {
            offBase1[k] = p;
            int e = m.getInt(p + c1 * 4);                 // off_k[count]
            nbrBase1[k] = p + (c1 + 1) * 4;
            p = nbrBase1[k] + e * 4;
        }
        r1 = m;
    }
    static int[] nbrsRBH1(int n, int k) {
        int lo = r1.getInt(offBase1[k] + n*4), hi = r1.getInt(offBase1[k] + (n+1)*4);
        int[] a = new int[hi - lo];
        for (int i = lo; i < hi; i++) a[i-lo] = r1.getInt(nbrBase1[k] + i*4);
        Arrays.sort(a); return a;
    }
    static int[] nbrsRBH2(int n, int k) {
        int lo = HnswGraph.nbrLo(n, k), hi = HnswGraph.nbrHi(n, k);
        int[] a = new int[hi - lo];
        for (int i = lo; i < hi; i++) a[i-lo] = HnswGraph.nbrAt(k, i);
        Arrays.sort(a); return a;
    }

    public static void main(String[] args) throws IOException {
        String golden = args.length > 0 ? args[0] : "src/main/resources/hnsw.rbh1.golden";
        String rbh2   = args.length > 1 ? args[1] : "src/main/resources/hnsw.bin";
        openRBH1(golden);
        HnswGraph.mmap(new File(rbh2));
        if (HnswGraph.count != c1 || HnswGraph.maxLevel != maxL1)
            { System.out.println("header divergente RBH1≠RBH2 -> FAIL"); System.exit(1); }

        long div = 0;
        for (int n = 0; n < c1; n++)
            for (int k = 0; k <= maxL1; k++)
                if (!Arrays.equals(nbrsRBH1(n,k), nbrsRBH2(n,k))) div++;
        System.out.printf("Rbh2Equiv: %d divergencias / %d nos -> %s%n",
                div, c1, div == 0 ? "PASS" : "FAIL");
        if (div != 0) System.exit(1);
    }
}
