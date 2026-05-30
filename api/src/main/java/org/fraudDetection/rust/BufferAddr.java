package org.fraudDetection.rust;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.Buffer;

/**
 * Lê o endereço base cru de um buffer direct/mapped via {@code sun.nio.ch.DirectBuffer.address()}.
 * Mesmo padrão de reflection do {@link FdWrap} (privateLookupIn + --add-opens java.base/sun.nio.ch;
 * reflect-config registra DirectBuffer p/ o native-image). Usado pela Onda 32 (mlock do índice).
 */
public final class BufferAddr {
    private static final MethodHandle ADDRESS;
    static {
        try {
            Class<?> db = Class.forName("sun.nio.ch.DirectBuffer");
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(db, MethodHandles.lookup());
            ADDRESS = l.findVirtual(db, "address", MethodType.methodType(long.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static long addressOf(Buffer b) throws Throwable {
        return (long) ADDRESS.invoke(b);
    }

    private BufferAddr() {}
}
