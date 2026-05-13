package org.fraudDetection;

import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.NioServer;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        ConnectionState s = new ConnectionState();
        System.out.println("readBuffer cap:    " + s.readBuffer.capacity());   // 4096
        System.out.println("writeBuffer cap:   " + s.writeBuffer.capacity());  //  512
        System.out.println("readBuffer direct: " + s.readBuffer.isDirect());   // true
        new NioServer(9999).start();
    }
}
