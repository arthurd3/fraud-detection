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
    public static final int METHOD_UNKNOW = 0;
    public static final int METHOD_GET = 1;
    public static final int METHOD_POST = 2;

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

    public final float[] queryVector = new float[14];

    public final float[] knnDist = new float[5];
    public final boolean[] knnFraud = new boolean[5];

    public final byte[] queryQ   = new byte[16];
    public final byte[] vScratch = new byte[16];

    // Onda 7 v2: permuted i16 query for the EXACT KD-tree (lanes 14-19 zero).
    // Filled by KdTree.search from queryVector; not cleared on reset (overwritten
    // each request).
    public final short[] queryQ16 = new short[20];

    // Onda 3: top-5 ids do HNSW (lazy; zero-alloc após 1ª request; não limpa no reset)
    public int[] knn5;

    // Onda 17 (ships Onda 15 parser code per user decision to validate on Mac Mini):
    // scratch buffer for FraudRequestParser.indexKeys() — single-pass body scan
    // that locates up to 5 JSON keys in one sweep. Reused across top-level (5 keys)
    // and sub-object passes (≤3 keys, top slots used). Pre-allocated once; indexKeys
    // overwrites every entry per call so no reset needed. G2 ExactAgree proves
    // semantic equivalence to the previous N-call findKeyExact pattern.
    public final int[] keyOffsets = new int[5];

    public int fraudCount = 0;

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
        headerNameEnd = -1;   
        fraudCount = 0;       
    }
}
