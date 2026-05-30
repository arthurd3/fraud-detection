package org.fraudDetection;

import org.fraudDetection.knn.KdTree;
import org.fraudDetection.server.FdReceiver;
import org.fraudDetection.server.NioServer;

public class Main {
    static String dataPath() {
        String p = System.getProperty("DATA_PATH");
        if (p == null) p = System.getenv("DATA_PATH");
        return (p == null || p.isEmpty()) ? "src/main/resources" : p;
    }
    public static void main(String[] args) throws Exception {
        String d = dataPath();
        long t0 = System.currentTimeMillis();
        // Onda 7 v2: EXACT KD-tree (RKD6) mmap. Decisão de fraude byte-idêntica
        // ao ground truth oficial. INALTERADO no modo lapada (E=0 preservado).
        KdTree.load(d + "/references.kdt");
        System.out.println("kdtree loaded: " + KdTree.INSTANCE.size()
                + " nós (" + (System.currentTimeMillis() - t0) + " ms)");
        // Onda 32 (p99): pina o índice mmap'd p/ matar a cauda de page-fault
        // (best-effort; no-op em heap mode; E=0 intacto). Após KdTree.load.
        KdTree.INSTANCE.mlockIndex();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;

        String fdSock = System.getenv("FD_SOCKET");
        if (fdSock != null && !fdSock.isEmpty()) {
            // ===== Modo lapada (FD-passing) =====
            // O lapada (forwarder L4 Rust) aceita o TCP :9999 e passa o fd do
            // cliente via SCM_RIGHTS pro Unix socket $FD_SOCKET. A API recebe o
            // fd (fd_next_client @CFunction), embrulha em SocketChannel (FdWrap)
            // e serve a request — o NioServer/parse/busca/keep-alive não mudam.
            int rc = org.fraudDetection.rust.RustSearch.fdListenerInit();
            if (rc != 0) {
                System.err.println("fd_listener_init falhou rc=" + rc + " (FD_SOCKET=" + fdSock + ")");
                System.exit(4);
            }
            System.out.println("api: modo lapada FD_SOCKET=" + fdSock);
            NioServer server = new NioServer(port);
            Thread recv = new Thread(new FdReceiver(server), "fd-receiver");
            recv.setDaemon(false);
            recv.start();
            server.startLapadaMode();
        } else {
            // ===== Modo TCP (fallback/local sem lapada) =====
            new NioServer(port).start();
        }
    }
}
