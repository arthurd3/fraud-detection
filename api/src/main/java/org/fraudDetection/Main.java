package org.fraudDetection;

import org.fraudDetection.server.NioServer;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        new NioServer(9999).start();
    }
}
