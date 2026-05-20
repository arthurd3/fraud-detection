package org.fraudDetection.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;


public class NioServer {
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private static final byte[] PATH_READY = {'/','r','e','a','d','y'};
    private static final byte[] PATH_FRAUD = {'/','f','r','a','u','d','-','s','c','o','r','e'};

    public NioServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        // SELECTOR - BLOCK WAITING I/O EVENTS
        selector = Selector.open();

        // LISTENING SOCKET
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false); // binding after config non-blocking
        serverChannel.register(selector, SelectionKey.OP_ACCEPT); // REGISTER ON SELECTOR

        System.out.println("api: Listening on port " + port);

        // Infinite loop reactor
        while(true){

            selector.select();

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while(it.hasNext()){
                SelectionKey key = it.next();
                it.remove();
                if(!key.isValid()) continue;

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

        // Onda 12 Phase C — TCP_NODELAY explicit on accepted sockets. Java NIO
        // does not disable Nagle's algorithm by default; modern Linux usually
        // auto-disables it for small writes, but explicit guards the worst-case
        // ~200 µs delay on the canned-response write path that follows every
        // request. Cost: zero (one setsockopt() per accept, not per request).
        socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);

        // Connection State via TCP CONNECTION - REUSED FOR ALL CONN REQUESTS
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