package org.fraudDetection.dataset;

import org.fraudDetection.knn.Quantizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.GZIPInputStream;

/**
 * Onda 2b: dataset int8 RB2 (padded-16) off-heap via MappedByteBuffer.
 * Cada vetor = 16 bytes: 14 reais + 2 zero (pad p/ SIMD sem máscara).
 */
public final class MmapDataset {

    public static final int DIMS = 14;
    private static final int STRIDE = 16;                 // 14 reais + 2 pad
    private static final int HEADER = 12;                 // magic(4)+count(4)+dims(4)
    private static final byte[] MAGIC = {'R', 'B', '2', 0};

    public static MappedByteBuffer data;
    public static int count;
    public static int lblBase;                            // HEADER + count*STRIDE

    private MmapDataset() {}

    public static void load(String gzPath, String binPath) throws IOException {
        File bin = new File(binPath);
        if (!bin.exists() || !isRB2(bin)) {
            System.out.println("references.bin ausente/incompativel — gerando RB2 do .gz (1x)...");
            build(gzPath, bin);
        }
        mmap(bin);
        System.out.println("dataset int8 RB2 mmap: " + count + " vetores ("
                + bin.length() + " bytes off-heap)");
    }

    public static int recBase(int i) { return HEADER + i * STRIDE; }
    public static boolean fraud(int i) { return data.get(lblBase + i) != 0; }

    // troca RB1->RB2 sozinho: se magic/dims não baterem, regenera
    private static boolean isRB2(File bin) {
        try (RandomAccessFile r = new RandomAccessFile(bin, "r")) {
            if (r.length() < HEADER) return false;
            byte[] m = new byte[4];
            r.readFully(m);
            return m[0] == MAGIC[0] && m[1] == MAGIC[1]
                    && m[2] == MAGIC[2] && m[3] == MAGIC[3]
                    && r.readInt() >= 0 && r.readInt() == DIMS;
        } catch (IOException e) {
            return false;
        }
    }

    private static void mmap(File bin) throws IOException {
        try (FileChannel ch = FileChannel.open(bin.toPath())) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            for (int i = 0; i < 4; i++)
                if (m.get(i) != MAGIC[i]) throw new IOException("magic invalido (esperado RB2)");
            int c = m.getInt(4);
            int dims = m.getInt(8);
            if (dims != DIMS) throw new IOException("dims=" + dims + " (esperado 14)");
            count = c;
            lblBase = HEADER + count * STRIDE;
            data = m;
        }
    }

    private static void build(String gzPath, File bin) throws IOException {
        byte[] labels = new byte[1 << 20];
        int n = 0;
        byte[] rec = new byte[STRIDE];                    // [14],[15] ficam 0 sempre
        float[] f = new float[DIMS];

        try (RandomAccessFile raf = new RandomAccessFile(bin, "rw")) {
            raf.setLength(0);
            raf.write(MAGIC);                             // [0..3]
            raf.writeInt(0);                              // [4..7] count placeholder
            raf.writeInt(DIMS);                           // [8..11] dims = 14

            try (InputStream in = new BufferedInputStream(
                    new GZIPInputStream(new FileInputStream(gzPath), 1 << 16), 1 << 16)) {

                int c = skipTo(in, '[');
                if (c < 0) throw new IOException("dataset vazio / sem '['");

                while (true) {
                    c = nextNonWs(in);
                    if (c == ']' || c < 0) break;
                    if (c != '{') continue;

                    skipTo(in, '[');
                    for (int k = 0; k < DIMS; k++) f[k] = readFloat(in);
                    for (int k = 0; k < DIMS; k++) rec[k] = Quantizer.q(f[k]);
                    raf.write(rec);                       // 16 bytes (14 + 2 zero)

                    skipTo(in, '"'); skipTo(in, '"'); skipTo(in, '"');
                    int first = in.read();
                    skipTo(in, '"');
                    skipTo(in, '}');

                    if (n == labels.length) {
                        byte[] nl = new byte[labels.length << 1];
                        System.arraycopy(labels, 0, nl, 0, n);
                        labels = nl;
                    }
                    labels[n++] = (byte) (first == 'f' ? 1 : 0);
                    if ((n % 500_000) == 0) System.out.println("  quantizados " + n + "...");
                }
            }

            raf.write(labels, 0, n);
            raf.seek(4);
            raf.writeInt(n);
            raf.getFD().sync();
        }
    }

    private static int skipTo(InputStream in, int target) throws IOException {
        int b;
        while ((b = in.read()) != -1) if (b == target) return b;
        return -1;
    }
    private static int nextNonWs(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1)
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return b;
        return -1;
    }
    private static float readFloat(InputStream in) throws IOException {
        int b = nextNonWs(in);
        boolean neg = false;
        if (b == '-') { neg = true; b = in.read(); }
        double val = 0;
        while (b >= '0' && b <= '9') { val = val * 10 + (b - '0'); b = in.read(); }
        if (b == '.') {
            b = in.read();
            double sc = 0.1;
            while (b >= '0' && b <= '9') { val += (b - '0') * sc; sc *= 0.1; b = in.read(); }
        }
        return (float) (neg ? -val : val);
    }
}