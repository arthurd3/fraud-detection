package org.fraudDetection;

import org.fraudDetection.server.ServerHTTP;

public class Main {

    public static void main(String[] args){
        final ServerHTTP server = new ServerHTTP();
        if(server.init()){
            System.out.println("Server Initialized with success");
            server.getServer().start();
        }else{
            System.out.println("Error on initialized Server");
        }
    }
}