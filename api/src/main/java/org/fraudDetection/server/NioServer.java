package org.fraudDetection.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;


public class NioServer {
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;

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

    }


    private void read(SelectionKey serverKey) throws IOException{

    }


    private void write(SelectionKey serverKey) throws IOException{

    }
}