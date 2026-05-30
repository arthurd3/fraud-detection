package org.fraudDetection.rust;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/** Spike Fase 0.5: prova reflection-wrap fd->SocketChannel no native-image. TEMPORÁRIO. */
public final class SpikeReactor {
    public static void main(String[] args) throws Throwable {
        ServerSocketChannel srv = ServerSocketChannel.open();
        srv.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ((InetSocketAddress) srv.getLocalAddress()).getPort();

        SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        SocketChannel accepted = srv.accept();

        // extrai o fd cru do accepted, embrulha num NOVO channel (o teste do Plano A).
        int rawFd = FdWrap.extractRawFd(accepted);
        System.out.println("SPIKE extractRawFd=" + rawFd);
        SocketChannel wrapped = FdWrap.wrapFd(rawFd);
        wrapped.configureBlocking(true);

        client.write(ByteBuffer.wrap("PING".getBytes()));
        ByteBuffer buf = ByteBuffer.allocate(4);
        while (buf.hasRemaining()) {
            if (wrapped.read(buf) < 0) break;
        }
        String got = new String(buf.array(), 0, buf.position());
        System.out.println("SPIKE wrapped.read=" + got);
        if (!"PING".equals(got)) {
            System.err.println("SPIKE-A FAIL");
            System.exit(1);
        }
        System.out.println("SPIKE-A OK (reflection-wrap funciona no native-image -> Plano A)");
    }
}
