package org.fraudDetection.knn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Persist / reload the EXACT {@link KdTree}. Binary format {@code RKD3}, all
 * multi-byte values LITTLE-ENDIAN. Ported from jvmoonshot KdTreeIO.save with
 * the {@code origId} section KEPT (needed for the C origId tie-break).
 *
 * <pre>
 *   header : "RKD3"(4) + ver i32(=3) + n i32 + dims i32(=14) + stride i32(=20) + root i32(=0)
 *   pts    : short[n*20]                (14 permuted dims + leftAndDim + right + fraud + pad)
 *   origId : int32[n]
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

    static final byte[] MAGIC = {'R', 'K', 'D', '3'};
    static final int VERSION = 3;
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
            writeInts(ch, tree.origId, tree.n);

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
            readInts(ch, origId, n);

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
     * Production loader: mmap {@code pts} (n*20*2 bytes) and {@code origId}
     * (n*4 bytes) off the JVM heap; topSlot/topBbox on-heap. All reads are
     * absolute little-endian via {@link MappedByteBuffer} (Java 21 portable,
     * no FFM / no Unsafe).
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
            long origLen = (long) n * 4L;
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

    /** True if {@code file} is a valid RKD3 for exactly {@code expectN} nodes. */
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
            throw new IOException("bad magic (expected RKD3)");
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
}
