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






}
