package org.fraudDetection.rust;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;

/** Binding p/ o motor Rust (Fase 0: só fd_ping; Fase 1 ganha fd_init/fd_search). */
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

    private RustSearch() {}
}
