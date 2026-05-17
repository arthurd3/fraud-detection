package org.fraudDetection.knn;

/** Scratch HNSW único — reator single-thread (NÃO thread-safe; 1 request por vez). */
public final class HnswScratch {
    private HnswScratch() {}

    public static int   count;
    public static int[] visited;          // visited[n]==gen => visto NESTA query
    public static int   gen;

    // candidatos: MIN-heap por dist (explora o mais perto primeiro)
    public static int[] cN, cD; public static int cSize;
    // resultado: MAX-heap por dist (raiz = mais distante; evict quando passa de ef)
    public static int[] rN, rD; public static int rSize;

    // buffers de record RB2 (16 bytes) p/ a distância
    public static byte[] bufA, bufB;

    private static final int CAP = 1 << 15;   // folgado p/ ef<=200 neste dataset

    public static void init(int n) {
        count = n;
        visited = new int[n]; gen = 0;
        cN = new int[CAP]; cD = new int[CAP];
        rN = new int[CAP]; rD = new int[CAP];
        bufA = new byte[16]; bufB = new byte[16];
    }
    public static void newQuery() { gen++; cSize = 0; rSize = 0; }
    public static boolean seen(int n) { return visited[n] == gen; }
    public static void mark(int n)   { visited[n] = gen; }

    // ---- MIN-heap candidatos ----
    public static void cPush(int node, int dist) {
        int i = cSize++; cN[i] = node; cD[i] = dist;
        while (i > 0) { int p = (i-1) >> 1; if (cD[p] <= cD[i]) break; sw(cN,cD,p,i); i = p; }
    }
    public static int cMinDist() { return cD[0]; }
    public static int cPopNode() {
        int top = cN[0]; int last = --cSize;
        cN[0] = cN[last]; cD[0] = cD[last];
        int i = 0;
        while (true) { int l=2*i+1, r=l+1, mn=i;
            if (l<cSize && cD[l]<cD[mn]) mn=l;
            if (r<cSize && cD[r]<cD[mn]) mn=r;
            if (mn==i) break; sw(cN,cD,mn,i); i=mn; }
        return top;
    }
    // ---- MAX-heap resultado ----
    public static void rPush(int node, int dist) {
        int i = rSize++; rN[i]=node; rD[i]=dist;
        while (i>0) { int p=(i-1)>>1; if (rD[p]>=rD[i]) break; sw(rN,rD,p,i); i=p; }
    }
    public static int rMaxDist() { return rD[0]; }
    public static int rMaxNode() { return rN[0]; }
    public static void rPopMax() {
        int last = --rSize; rN[0]=rN[last]; rD[0]=rD[last];
        int i=0;
        while (true){ int l=2*i+1,r=l+1,mx=i;
            if (l<rSize && rD[l]>rD[mx]) mx=l;
            if (r<rSize && rD[r]>rD[mx]) mx=r;
            if (mx==i) break; sw(rN,rD,mx,i); i=mx; }
    }
    private static void sw(int[] a, int[] b, int x, int y) {
        int t=a[x]; a[x]=a[y]; a[y]=t; t=b[x]; b[x]=b[y]; b[y]=t;
    }
}
