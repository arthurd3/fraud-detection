package org.fraudDetection.dataset;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public final class MmapDataset {

    public static float[][] vectors;
    public static boolean[] isFraud;
    public static int count;

    private MmapDataset(){
    }


    public static void load(String gzPath) throws IOException{
        int cap = 1 << 20; 
        float[][] vs = new float[cap][];
        boolean[] fs = new boolean[cap];
        int n = 0;


        try(InputStream in = new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(gzPath), 1 << 16), 1 << 16)){
            
            int c = skipTo(in, '[');
            if(c < 0) throw new IOException("Empty dataset / without [")

            while(true){
                c = nextNonWs(in);
                if(c == ']' || c < 0) break;
                if(c != '{') {
                    if(c == ',') continue;
                    continue;
                }

                float[] vec = new float[14];
                skipTo(in , '[');
                for(int k = 0; k < 14; k++){
                    vec[k] = readFloat(in);
                }

                skipTo(in, '"');
                skipTo(in, '"');
                skipTo(in, '"');
                int first = in.read();
                boolean fraud = (first == 'f');
                skipTo(in, '"');
                skipTo(in, '}');

                if (n == cap) {                          // grow
                    cap <<= 1;
                    float[][] nv = new float[cap][];   System.arraycopy(vs, 0, nv, 0, n); vs = nv;
                    boolean[] nf = new boolean[cap];   System.arraycopy(fs, 0, nf, 0, n); fs = nf;
                }

                vs[n] = vec;
                fs[n] = fraud;
                n++;
                if ((n % 500_000) == 0) System.out.println("  loaded " + n + " vectors...");

            }

            vectors = vs;
            isFraud = fs;
            count = n;
        }

    }


    private static int skipTo(InputStream in , int target) throws IOException{
        int b;

        while((b == in.read()) != -1) if (b == target) return b;
        return -1;
    }
    

    private static int nextNonWs(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return b;
        }
        return -1;
    }

   
    private static float readFloat(InputStream in) throws IOException {
        int b = nextNonWs(in);
        boolean neg = false;
        if (b == '-') { neg = true; b = in.read(); }
        double val = 0;
        while (b >= '0' && b <= '9') { val = val * 10 + (b - '0'); b = in.read(); }
        if (b == '.') {
            b = in.read();
            double scale = 0.1;
            while (b >= '0' && b <= '9') { val += (b - '0') * scale; scale *= 0.1; b = in.read(); }
        }
        return (float) (neg ? -val : val);
    }





}
