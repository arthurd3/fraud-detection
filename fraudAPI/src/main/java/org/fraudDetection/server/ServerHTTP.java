package org.fraudDetection.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class ServerHTTP {

    private HttpServer server;

    public boolean init(){
        try{
            this.server = HttpServer.create(new InetSocketAddress(9999),0);
            return true;
        }catch (IOException io){
            return false;
        }
    }

    public HttpServer getServer(){
        if(server.getAddress() != null) return this.server;
        throw new RuntimeException("Server not already initialized");
    }
}
