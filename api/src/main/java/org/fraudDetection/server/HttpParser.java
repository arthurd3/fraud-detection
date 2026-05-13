package org.fraudDetection.server;

import java.nio.ByteBuffer;

public class HttpParser {
    public static final int PARSE_INCOMPLETE = 0;
    public static final int PARSE_DONE = 1;
    public static final int PARSE_ERROR = 2;

    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';
    private static final byte SPACE = (byte) ' ';
    private static final byte COLON = (byte) ':';

    private static final byte[] HDR_CONTENT_LENGTH = {
            'c','o','n','t','e','n','t','-','l','e','n','g','t','h'
    };

    public static int parse(ConnectionState state) {
        ByteBuffer buf = state.readBuffer;
        int limit = buf.position();
        int i = state.parserPosition;

        while (i < limit) {
            byte b = buf.get(i);

            switch (state.parserState) {
                case ConnectionState.STATE_METHOD -> {
                    if (b == SPACE) {
                        state.methodCode = matchMethod(buf, 0, i);
                        if (state.methodCode == ConnectionState.METHOD_UNKNOW) return PARSE_ERROR;
                        state.parserState = ConnectionState.STATE_PATH;
                        state.pathStart   = i + 1;
                    }
                }
                case ConnectionState.STATE_PATH -> {
                    if (b == SPACE) {
                        state.pathEnd     = i;
                        state.parserState = ConnectionState.STATE_VERSION;
                    }
                }
                case ConnectionState.STATE_VERSION -> {
                    if (b == LF) state.parserState = ConnectionState.STATE_HEADER_LINE;
                }
                case ConnectionState.STATE_HEADER_LINE -> {
                    if (b == CR) {
                    } else if (b == LF) {
                        state.bodyOffset = i + 1;
                        if (state.contentLength == 0) {
                            state.parserState    = ConnectionState.STATE_DONE;
                            state.parserPosition = i + 1;
                            return PARSE_DONE;
                        }
                        state.parserState = ConnectionState.STATE_BODY;
                    } else {
                        state.headerNameStart = i;
                        state.parserState     = ConnectionState.STATE_HEADER_NAME;
                    }
                }
                case ConnectionState.STATE_HEADER_NAME -> {
                    if (b == COLON) {
                        state.headerNameEnd = i;
                        state.parserState   = ConnectionState.STATE_HEADER_VALUE;
                    }
                }
                case ConnectionState.STATE_HEADER_VALUE -> {
                    if (b == LF) {
                        if (headerEquals(buf, state.headerNameStart, state.headerNameEnd, HDR_CONTENT_LENGTH)) {
                            int parsed = parseDecimal(buf, state.headerNameEnd + 1, i - 1);
                            if (parsed < 0) return PARSE_ERROR;
                            state.contentLength = parsed;
                        }
                        state.parserState = ConnectionState.STATE_HEADER_LINE;
                    }
                }
                case ConnectionState.STATE_BODY -> {
                    int bodyBytesAvailable = limit - state.bodyOffset;
                    if (bodyBytesAvailable >= state.contentLength) {
                        state.parserState    = ConnectionState.STATE_DONE;
                        state.parserPosition = state.bodyOffset + state.contentLength;
                        return PARSE_DONE;
                    }
                    state.parserPosition = limit;
                    return PARSE_INCOMPLETE;
                }
                case ConnectionState.STATE_DONE -> {
                    return PARSE_DONE;
                }
            }
            i++;
        }

        state.parserPosition = i;
        return PARSE_INCOMPLETE;
    }


    private static int matchMethod(ByteBuffer buf, int start, int end){
        int len = end - start;
        if (len == 3
                && buf.get(start) == 'G'
                && buf.get(start+1) == 'E'
                && buf.get(start+2) == 'T'){
            return ConnectionState.METHOD_GET;
        }
        if (len == 4
                && buf.get(start) == 'P'
                && buf.get(start+1) == 'O'
                && buf.get(start+2) == 'S'
                && buf.get(start+2) == 'T'){
            return ConnectionState.METHOD_POST;
        }
        return ConnectionState.METHOD_UNKNOW;
    }


    //COMPARE HEADER NAME CASE INSENSITIVE WITH A EXPECTED IN LOWERCASE
    private static boolean headerEquals(ByteBuffer buf, int start, int end, byte[] expectedLower){
        if(end - start != expectedLower.length) return false;
        for(int j = 0; j< expectedLower.length; j++){
            byte b = buf.get(start + j);

            if( b >= 'A' && b <= 'Z') b |= 0x20;
            if(b != expectedLower[j]) return false;
        }
        return true;
    }


    private static int parseDecimal(ByteBuffer buf , int start , int end){
        int value = 0;
        boolean anyDigit = false;
        for(int j = start; j< end; j++){
            byte b = buf.get(j);
            if(b == ' ' || b == CR) continue;
            if(b < '0' || b > '9') return -1;
            value = value * 10 + (b - '0');
            anyDigit = true;
        }
        return anyDigit ? value : -1;
    }
}
