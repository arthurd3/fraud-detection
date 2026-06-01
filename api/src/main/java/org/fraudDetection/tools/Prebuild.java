package org.fraudDetection.tools;

import org.fraudDetection.knn.KdTree;
import org.fraudDetection.knn.KdTreeBuilder;
import org.fraudDetection.knn.KdTreeIO;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Offline pre-build (never runs in the container). Builds {@code references.kdt}
 * — the EXACT KD-tree (RKD6), Onda 7 v2. The source vectors are streamed from
 * references.json.gz (byte-by-byte float parser: double accumulate → float;
 * references.json.gz values are exact multiples of 1e-4).
 */
public final class Prebuild {
    private Prebuild() {}

    private static final int DIMS = 14;
    private static final int N = 3_000_000; // contest reference count

    public static void main(String[] args) throws Exception {
        String d = args.length > 0 ? args[0]
                : System.getProperty("DATA_PATH",
                    System.getenv().getOrDefault("DATA_PATH", "src/main/resources"));
        long t0 = System.currentTimeMillis();

        // ── EXACT KD-tree (references.kdt) ───────────────────────────────────────────
        String kdt = d + "/references.kdt";
        if (KdTreeIO.isValid(Path.of(kdt), N)) {
            System.out.println("references.kdt já válido (" + N + " nós) — pulando build");
        } else {
            System.out.println("construindo references.kdt (EXACT KD-tree, RKD4 BFS-blocked)...");
            float[] vecs = new float[N * DIMS];
            boolean[] fraud = new boolean[N];
            int n = streamGz(d + "/references.json.gz", vecs, fraud);
            if (n != N) {
                System.out.println("AVISO: lidos " + n + " refs (esperado " + N
                        + ") — reconstruindo arrays no tamanho exato");
                float[] v2 = new float[n * DIMS];
                boolean[] f2 = new boolean[n];
                System.arraycopy(vecs, 0, v2, 0, n * DIMS);
                System.arraycopy(fraud, 0, f2, 0, n);
                vecs = v2; fraud = f2;
            }
            KdTree tree = KdTreeBuilder.build(n, vecs, fraud);
            KdTreeIO.save(tree, Path.of(kdt));
            System.out.println("references.kdt pronto: " + n + " nós, "
                    + new File(kdt).length() + " bytes");
        }

        System.out.println("Prebuild concluído em "
                + ((System.currentTimeMillis() - t0) / 1000) + "s");
    }

    /** Stream references.json.gz → vecs (n*14 semantic) + fraud[n]; returns n. */
    private static int streamGz(String gzPath, float[] vecs, boolean[] fraud) throws IOException {
        int n = 0;
        try (InputStream in = new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(gzPath), 1 << 16), 1 << 16)) {
            if (skipTo(in, '[') < 0) throw new IOException("dataset vazio / sem '['");
            while (true) {
                int c = nextNonWs(in);
                if (c == ']' || c < 0) break;
                if (c != '{') continue;
                skipTo(in, '[');                                  // "vector":[
                int base = n * DIMS;
                for (int k = 0; k < DIMS; k++) vecs[base + k] = readFloat(in);
                skipTo(in, '"'); skipTo(in, '"'); skipTo(in, '"'); // ..."label":"
                int first = in.read();                             // 'f' or 'l'
                skipTo(in, '"');
                skipTo(in, '}');
                fraud[n] = (first == 'f');
                n++;
                if ((n % 500_000) == 0) System.out.println("  lidos " + n + " refs...");
            }
        }
        return n;
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
