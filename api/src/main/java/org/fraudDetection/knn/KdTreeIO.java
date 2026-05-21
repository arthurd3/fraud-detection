package org.fraudDetection.knn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Persist / reload the EXACT {@link KdTree}. Binary format {@code RKD6}, all
 * multi-byte values LITTLE-ENDIAN. Ported from jvmoonshot KdTreeIO.save with
 * the {@code origId} section KEPT (needed for the C origId tie-break); nodes
 * BFS-blocked (RKD4), {@code origId} packed uint24 (RKD5), pad lane dropped
 * (RKD6 / Onda 21 → STRIDE 20→19, −6 MB mmap pressure under 159 MiB cgroup).
 *
 * <pre>
 *   header : "RKD6"(4) + ver i32(=6) + n i32 + dims i32(=14) + stride i32(=19) + root i32(=0)
 *   pts    : short[n*19]                (14 permuted dims + leftAndDim + right + fraud)
 *   origId : uint24[n] LE               (ids < 3M < 2^24, lossless; kept from RKD5)
 *   meta   : topNodeCount i32
 *   topBbox: short[topNodeCount*32]
 *   topSlot: int32[n]
 * </pre>
 *
 * <p>Heap loader ({@link #load}) reads everything into arrays. Production loader
 * ({@link #loadMmap}) maps {@code pts} off-heap via {@link MappedByteBuffer}
 * (absolute LE {@code getShort(int)}/{@code getInt(int)} — no FFM, no Unsafe;
 * mirrors {@link org.fraudDetection.dataset.MmapDataset}); origId is mapped too
 * (needed for tie-break), topSlot/topBbox kept on-heap.
 */
public final class KdTreeIO {

    // RKD6 (Onda 21): RKD5 with the unused PAD lane (19) dropped — STRIDE 20→19
    // ⇒ −6 MB mmap (114 MB pts vs 120 MB). Direct attack on hypothesis 2 of the
    // Onda 20 diagnostic (mmap eviction under 159 MiB cgroup contributing 25-30 ms
    // of the 35 ms p99 tail). Lossless: pad was written by KdTreeBuilder but never
    // read on the hot path (only LANE_LEFT_DIM=14 / LANE_RIGHT=16 / LANE_FRAUD=18
    // are consumed). Bumping magic+ver invalidates any RKD3/RKD4/RKD5 .kdt so
    // Prebuild regenerates it under the new STRIDE on next boot/test.
    static final byte[] MAGIC = {'R', 'K', 'D', '6'};
    static final int VERSION = 6;
    private static final int HEADER_BYTES = 4 + 5 * 4; // magic + ver,n,dims,stride,root
    private static final int IO_CHUNK = 8 * 1024 * 1024;

    private KdTreeIO() {}

    public static void save(KdTree tree, Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer hdr = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            hdr.put(MAGIC);
            hdr.putInt(VERSION);
            hdr.putInt(tree.n);
            hdr.putInt(KdLayout.DIMS);
            hdr.putInt(KdLayout.STRIDE);
            hdr.putInt(0); // root always 0
            hdr.flip();
            writeFully(ch, hdr);

            writeShorts(ch, tree.pts, tree.n * KdLayout.STRIDE);
            writeInts24(ch, tree.origId, tree.n);

            ByteBuffer meta = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            meta.putInt(tree.topNodeCount);
            meta.flip();
            writeFully(ch, meta);

            writeShorts(ch, tree.topBbox, tree.topNodeCount * KdLayout.STRIDE_BBOX);
            writeInts(ch, tree.topSlot, tree.n);
        }
    }

    /** Heap loader (on-heap arrays) — used by equivalence/brute tests. */
    public static KdTree load(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            int n = readAndCheckHeader(ch);

            short[] pts = new short[n * KdLayout.STRIDE];
            readShorts(ch, pts, n * KdLayout.STRIDE);
            int[] origId = new int[n];
            readInts24(ch, origId, n);

            ByteBuffer meta = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, meta);
            meta.flip();
            int topNodeCount = meta.getInt();

            short[] topBbox = new short[topNodeCount * KdLayout.STRIDE_BBOX];
            readShorts(ch, topBbox, topNodeCount * KdLayout.STRIDE_BBOX);
            int[] topSlot = new int[n];
            readInts(ch, topSlot, n);

            return new KdTree(n, pts, origId, topSlot, topBbox, topNodeCount);
        }
    }

    /**
     * Production loader: mmap {@code pts} (n*STRIDE*2 bytes; STRIDE=19 in RKD6)
     * and {@code origId} (n*3 bytes, packed uint24) off the JVM heap; topSlot/
     * topBbox on-heap. All reads are absolute little-endian via
     * {@link MappedByteBuffer} (Java 21 portable, no FFM / no Unsafe).
     */
    public static KdTree loadMmap(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            int n = readAndCheckHeader(ch);

            long ptsOff = ch.position();
            long ptsLen = (long) n * KdLayout.STRIDE * 2L;
            MappedByteBuffer ptsBuf = ch.map(FileChannel.MapMode.READ_ONLY, ptsOff, ptsLen);
            ptsBuf.order(ByteOrder.LITTLE_ENDIAN);
            ch.position(ptsOff + ptsLen);

            long origOff = ch.position();
            long origLen = (long) n * 3L; // RKD5: origId packed uint24 LE
            MappedByteBuffer origBuf = ch.map(FileChannel.MapMode.READ_ONLY, origOff, origLen);
            origBuf.order(ByteOrder.LITTLE_ENDIAN);
            ch.position(origOff + origLen);

            ByteBuffer meta = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, meta);
            meta.flip();
            int topNodeCount = meta.getInt();

            short[] topBbox = new short[topNodeCount * KdLayout.STRIDE_BBOX];
            readShorts(ch, topBbox, topNodeCount * KdLayout.STRIDE_BBOX);
            int[] topSlot = new int[n];
            readInts(ch, topSlot, n);

            return new KdTree(n, ptsBuf, origBuf, topSlot, topBbox, topNodeCount);
        }
    }

    /** True if {@code file} is a valid RKD6 for exactly {@code expectN} nodes. */
    public static boolean isValid(Path file, int expectN) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return readAndCheckHeader(ch) == expectN;
        } catch (IOException e) {
            return false;
        }
    }

    private static int readAndCheckHeader(FileChannel ch) throws IOException {
        ByteBuffer hdr = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, hdr);
        hdr.flip();
        byte[] magic = new byte[4];
        hdr.get(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1]
                || magic[2] != MAGIC[2] || magic[3] != MAGIC[3])
            throw new IOException("bad magic (expected RKD6)");
        int ver = hdr.getInt();
        int n = hdr.getInt();
        int dims = hdr.getInt();
        int stride = hdr.getInt();
        int root = hdr.getInt();
        if (ver != VERSION || dims != KdLayout.DIMS || stride != KdLayout.STRIDE || root != 0)
            throw new IOException("unexpected ver/dims/stride/root: "
                    + ver + "/" + dims + "/" + stride + "/" + root);
        return n;
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) ch.write(buf);
    }

    private static void readFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new IOException("unexpected EOF");
        }
    }

    private static void writeShorts(FileChannel ch, short[] arr, int total) throws IOException {
        int elemsPerChunk = IO_CHUNK / 2;
        ByteBuffer buf = ByteBuffer.allocate(Math.min(Math.max(total, 1), elemsPerChunk) * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        int off = 0;
        while (off < total) {
            int len = Math.min(elemsPerChunk, total - off);
            buf.clear();
            buf.asShortBuffer().put(arr, off, len);
            buf.limit(len * 2);
            writeFully(ch, buf);
            off += len;
        }
    }

    private static void readShorts(FileChannel ch, short[] arr, int total) throws IOException {
        int elemsPerChunk = IO_CHUNK / 2;
        ByteBuffer buf = ByteBuffer.allocate(Math.min(Math.max(total, 1), elemsPerChunk) * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        int off = 0;
        while (off < total) {
            int len = Math.min(elemsPerChunk, total - off);
            buf.clear();
            buf.limit(len * 2);
            readFully(ch, buf);
            buf.flip();
            buf.asShortBuffer().get(arr, off, len);
            off += len;
        }
    }

    private static void writeInts(FileChannel ch, int[] arr, int total) throws IOException {
        int elemsPerChunk = IO_CHUNK / 4;
        ByteBuffer buf = ByteBuffer.allocate(Math.min(Math.max(total, 1), elemsPerChunk) * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        int off = 0;
        while (off < total) {
            int len = Math.min(elemsPerChunk, total - off);
            buf.clear();
            buf.asIntBuffer().put(arr, off, len);
            buf.limit(len * 4);
            writeFully(ch, buf);
            off += len;
        }
    }

    private static void readInts(FileChannel ch, int[] arr, int total) throws IOException {
        int elemsPerChunk = IO_CHUNK / 4;
        ByteBuffer buf = ByteBuffer.allocate(Math.min(Math.max(total, 1), elemsPerChunk) * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        int off = 0;
        while (off < total) {
            int len = Math.min(elemsPerChunk, total - off);
            buf.clear();
            buf.limit(len * 4);
            readFully(ch, buf);
            buf.flip();
            buf.asIntBuffer().get(arr, off, len);
            off += len;
        }
    }

    /** RKD5: write {@code arr[0..total)} as packed uint24 LE (values < 2^24). */
    private static void writeInts24(FileChannel ch, int[] arr, int total) throws IOException {
        int elemsPerChunk = IO_CHUNK / 3;
        ByteBuffer buf = ByteBuffer.allocate(Math.min(Math.max(total, 1), elemsPerChunk) * 3);
        int off = 0;
        while (off < total) {
            int len = Math.min(elemsPerChunk, total - off);
            buf.clear();
            for (int i = 0; i < len; i++) {
                int v = arr[off + i];
                buf.put((byte) v).put((byte) (v >>> 8)).put((byte) (v >>> 16));
            }
            buf.flip();
            writeFully(ch, buf);
            off += len;
        }
    }

    /** RKD5: read {@code total} packed uint24 LE into {@code arr}. */
    private static void readInts24(FileChannel ch, int[] arr, int total) throws IOException {
        int elemsPerChunk = IO_CHUNK / 3;
        ByteBuffer buf = ByteBuffer.allocate(Math.min(Math.max(total, 1), elemsPerChunk) * 3);
        int off = 0;
        while (off < total) {
            int len = Math.min(elemsPerChunk, total - off);
            buf.clear();
            buf.limit(len * 3);
            readFully(ch, buf);
            buf.flip();
            for (int i = 0; i < len; i++) {
                int b0 = buf.get() & 0xFF, b1 = buf.get() & 0xFF, b2 = buf.get() & 0xFF;
                arr[off + i] = b0 | (b1 << 8) | (b2 << 16);
            }
            off += len;
        }
    }
}
