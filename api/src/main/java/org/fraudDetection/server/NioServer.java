package org.fraudDetection.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;


public class NioServer {
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private static final byte[] PATH_READY = {'/','r','e','a','d','y'};
    private static final byte[] PATH_FRAUD = {'/','f','r','a','u','d','-','s','c','o','r','e'};

    // Modo lapada: canais (fds embrulhados pelo FdReceiver noutra thread) a
    // registrar no Selector. Thread-safe; drenado no início de cada iteração.
    private final ConcurrentLinkedQueue<SocketChannel> pending = new ConcurrentLinkedQueue<>();

    public NioServer(int port) {
        this.port = port;
    }

    /** Modo TCP (fallback/local sem lapada): a própria API aceita conexões em :port. */
    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("api: Listening on port " + port);
        reactorLoop();
    }

    /** Modo lapada (FD-passing): sem listener TCP; serve fds injetados pelo FdReceiver. */
    public void startLapadaMode() throws IOException {
        selector = Selector.open();
        System.out.println("api: modo lapada (FD-passing) — sem listener TCP");
        reactorLoop();
    }

    /** Chamado pelo FdReceiver (outra thread): enfileira o canal + acorda o selector. */
    public void injectChannel(SocketChannel ch) {
        pending.add(ch);
        selector.wakeup();
    }

    // Reactor single-thread (idêntico ao loop antigo) + dreno dos canais injetados.
    private void reactorLoop() throws IOException {
        while (true) {
            // Drena fds injetados (modo lapada) ANTES de bloquear no select.
            SocketChannel inj;
            while ((inj = pending.poll()) != null) {
                try {
                    inj.configureBlocking(false);
                    inj.register(selector, SelectionKey.OP_READ, new ConnectionState());
                } catch (IOException ex) {
                    try { inj.close(); } catch (IOException ignored) {}
                }
            }

            selector.select();

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;

                try {
                    if (key.isAcceptable())       accept(key);
                    else if (key.isReadable())    read(key);
                    else if (key.isWritable())    write(key);
                } catch (IOException ex) {
                    key.cancel();
                    try { key.channel().close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private void accept(SelectionKey serverKey) throws IOException{
        SocketChannel socketChannel = serverChannel.accept();
        if (socketChannel == null) return;   // defesa contra spurious wakeup do selector
        socketChannel.configureBlocking(false);

        // Onda 12 Phase C — TCP_NODELAY explicit on accepted sockets.
        socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);

        ConnectionState state = new ConnectionState();
        socketChannel.register(selector, SelectionKey.OP_READ, state);
    }


    private void read(SelectionKey key) throws IOException{
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();

        int bytesRead = channel.read(state.readBuffer);

        if(bytesRead == -1){
            key.cancel();
            channel.close();
            return;
        }
        if(bytesRead == 0){
            return;
        }

        int result = HttpParser.parse(state);

        if(result == HttpParser.PARSE_INCOMPLETE){
            return;
        }
        if(result == HttpParser.PARSE_ERROR){
            key.cancel();
            channel.close();
            return;
        }
        dispatch(state,key);
    }


    private void write(SelectionKey key) throws IOException{
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();

        channel.write(state.writeBuffer);

        if(!state.writeBuffer.hasRemaining()){
            state.reset();
            key.interestOps(SelectionKey.OP_READ);
        }
    }


    private void dispatch(ConnectionState state, SelectionKey key) {
        if (state.methodCode == ConnectionState.METHOD_GET
                && bytesEqual(state.readBuffer, state.pathStart, state.pathEnd, PATH_READY)) {
            org.fraudDetection.controllers.HealthController.handle(state, key);
            return;
        }
        if (state.methodCode == ConnectionState.METHOD_POST
                && bytesEqual(state.readBuffer, state.pathStart, state.pathEnd, PATH_FRAUD)) {
            org.fraudDetection.controllers.FraudController.handle(state, key);
            return;
        }
        key.cancel();
        try { key.channel().close(); } catch (IOException ignored) {}
    }

    private static boolean bytesEqual(java.nio.ByteBuffer buf, int start, int end, byte[] expected) {
        if (end - start != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (buf.get(start + i) != expected[i]) return false;
        }
        return true;
    }
}
