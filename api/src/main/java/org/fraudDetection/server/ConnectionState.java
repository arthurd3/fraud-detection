package org.fraudDetection.server;

import java.nio.ByteBuffer;

public class ConnectionState {

    // STATES OF HTTP PARSER
    public static final int STATE_METHOD = 0;
    public static final int STATE_PATH = 1;
    public static final int STATE_VERSION = 2;
    public static final int STATE_HEADER_LINE = 3;
    public static final int STATE_HEADER_NAME = 4;
    public static final int STATE_HEADER_VALUE = 5;
    public static final int STATE_BODY = 6;
    public static final int STATE_DONE = 7;

    // METHOD CODES ( WITHOUT STRING -- INT IS CHEAP)
    private static final int METHOD_UNKNOW = 0;
    private static final int METHOD_GET = 1;
    private static final int METHOD_POST = 2;

    // BUFFERS OFF-HEAP for I/O -- locate 1x and nevermore
    public final ByteBuffer readBuffer = ByteBuffer.allocateDirect(4096);
    public final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(512);

    // PARSER STATE WHO PERSISTS INTO READS ( TCP FRAGMENTATION )
    public int parserState = STATE_METHOD;
    public int parserPosition = 0; // NEXT BYTE TO READ IN READ-BUFFER

    // EXTRACT DATA EXTRACTS IN ACTUAL REQUEST
    public int methodCode = METHOD_UNKNOW;
    public int pathStart = -1;
    public int pathEnd = -1;
    public int contentLength = 0;
    public int bodyOffset = -1;
    public int headerNameStart = -1;
    public int headerNameEnd = -1;



    // PREPARE STATE TO NEXT REQUEST ( KEEP ALIVE )
    // NOT HAVE BUFFER RELOCATE - JUST REWIND/CLEAR IN INDEX'S
    public void reset(){
        readBuffer.clear();
        writeBuffer.clear();
        parserState = STATE_METHOD;
        parserPosition = 0;
        methodCode = METHOD_UNKNOW;
        pathEnd = -1;
        pathStart = -1;
        contentLength = 0;
        bodyOffset = -1;
        headerNameStart = -1;
    }
}
