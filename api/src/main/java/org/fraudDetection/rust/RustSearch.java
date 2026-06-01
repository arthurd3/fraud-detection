package org.fraudDetection.rust;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;

/** Binding p/ o motor Rust: busca (fd_init/fd_search) + helpers FD-passing/mlock. */
@CContext(FdDirectives.class)
public final class RustSearch {
    @CFunction("fd_ping")
    public static native int fdPing(int a, int b);

    /** Modo lapada: cria+bind+listen o Unix socket de $FD_SOCKET (1× no boot). 0=ok, <0=erro. */
    @CFunction("fd_listener_init")
    public static native int fdListenerInit();

    /** Modo lapada: BLOQUEANTE; recebe 1 fd de cliente do lapada (recvmsg SCM_RIGHTS). client_fd≥0 ou -1. */
    @CFunction("fd_next_client")
    public static native int fdNextClient();

    /** Onda 32: eleva RLIMIT_MEMLOCK p/ infinito (best-effort). 0=ok, -1=falha. */
    @CFunction("fd_raise_memlock_rlimit")
    public static native int fdRaiseMemlockRlimit();

    /** Onda 32: mlock(addr,len) numa região mmap'd já residente. 0=ok, -errno=falha. */
    @CFunction("fd_mlock_region")
    public static native int fdMlockRegion(long addr, long len);

    // ── Motor de busca KD-tree (port de KdTree.search) ──────────────────────────────
    /** Boot (modo rust): mmap de $DATA_PATH/references.kdt no Rust. 0=ok, <0=erro. */
    @CFunction("fd_init")
    public static native int kdInit();

    /** Por request: endereço de um buffer com 14 f32 LE (queryVector) → fraudCount 0..5. */
    @CFunction("fd_search")
    public static native int kdSearch(long qAddr);

    // Staging direct (14 f32 LE) reusado por request (reactor single-thread). LAZY em
    // RUNTIME: um direct ByteBuffer ESTÁTICO seria baked no image heap do native-image
    // (ponteiro p/ memória C do gerador, inválido em runtime → erro de build). Alocado
    // na 1ª request. O Rust lê f32 nativo (x86 LE) deste endereço (ordem LE casa).
    private static java.nio.ByteBuffer qbuf;
    private static long qAddr = 0L;

    /** Escreve queryVector[14] no staging e chama {@link #kdSearch} → fraudCount 0..5. */
    public static int searchVector(float[] q) {
        java.nio.ByteBuffer b = qbuf;
        if (b == null) {
            b = java.nio.ByteBuffer.allocateDirect(14 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            qbuf = b;
        }
        b.clear();
        for (int i = 0; i < 14; i++) b.putFloat(q[i]);
        long a = qAddr;
        if (a == 0L) {
            try {
                a = BufferAddr.addressOf(b);
            } catch (Throwable t) {
                throw new RuntimeException("BufferAddr falhou p/ a query staging", t);
            }
            qAddr = a;
        }
        return kdSearch(a);
    }

    private RustSearch() {}
}
