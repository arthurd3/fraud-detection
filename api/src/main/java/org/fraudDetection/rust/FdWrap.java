package org.fraudDetection.rust;

import java.io.FileDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

/** Spike Fase 0.5 (Plano A): embrulha um fd cru em SocketChannel via internals do NIO. */
public final class FdWrap {
    private static final MethodHandle CTOR;
    private static final VarHandle FD_FIELD;
    private static final MethodHandle GET_FD_VAL;

    static {
        try {
            Class<?> impl = Class.forName("sun.nio.ch.SocketChannelImpl");
            MethodHandles.Lookup il = MethodHandles.privateLookupIn(impl, MethodHandles.lookup());
            // JDK 21 (confirmado via javap): (SelectorProvider, ProtocolFamily, FileDescriptor, SocketAddress)
            CTOR = il.findConstructor(impl, MethodType.methodType(
                    void.class, SelectorProvider.class, ProtocolFamily.class,
                    FileDescriptor.class, SocketAddress.class));
            FD_FIELD = MethodHandles.privateLookupIn(FileDescriptor.class, MethodHandles.lookup())
                    .findVarHandle(FileDescriptor.class, "fd", int.class);
            Class<?> selch = Class.forName("sun.nio.ch.SelChImpl");
            GET_FD_VAL = MethodHandles.privateLookupIn(selch, MethodHandles.lookup())
                    .findVirtual(selch, "getFDVal", MethodType.methodType(int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int extractRawFd(SocketChannel ch) throws Throwable {
        return (int) GET_FD_VAL.invoke(ch);
    }

    public static SocketChannel wrapFd(int rawFd) throws Throwable {
        FileDescriptor jfd = new FileDescriptor();
        FD_FIELD.set(jfd, rawFd);
        return (SocketChannel) CTOR.invoke(SelectorProvider.provider(),
                StandardProtocolFamily.INET, jfd, (SocketAddress) null);
    }

    private FdWrap() {}
}
