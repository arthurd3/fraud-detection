package org.fraudDetection.server;

import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;

import org.fraudDetection.rust.FdWrap;
import org.fraudDetection.rust.RustSearch;

/**
 * Modo lapada: thread que recebe fds de cliente do lapada (via @CFunction
 * recvmsg SCM_RIGHTS), embrulha cada um em {@link SocketChannel} (FdWrap,
 * Plano A validado no G0.5) e injeta no {@link NioServer} (que registra no
 * Selector). Roda numa thread separada porque fd_next_client() é bloqueante.
 */
public final class FdReceiver implements Runnable {
    private final NioServer server;

    public FdReceiver(NioServer server) {
        this.server = server;
    }

    @Override
    public void run() {
        while (true) {
            int fd = RustSearch.fdNextClient();
            if (fd < 0) {
                // erro transitório / control conn reabrindo (fd_next_client já reaceita)
                continue;
            }
            SocketChannel ch = null;
            try {
                ch = FdWrap.wrapFd(fd);
                ch.configureBlocking(false);
                ch.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
                server.injectChannel(ch);
            } catch (Throwable t) {
                if (ch != null) {
                    try { ch.close(); } catch (Exception ignored) {}
                }
            }
        }
    }
}
