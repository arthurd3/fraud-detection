package org.fraudDetection.rust;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;

/** Binding p/ o motor Rust (Fase 0: só fd_ping; Fase 1 ganha fd_init/fd_search). */
@CContext(FdDirectives.class)
public final class RustSearch {
    @CFunction("fd_ping")
    public static native int fdPing(int a, int b);

    private RustSearch() {}
}
