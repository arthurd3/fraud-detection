package org.fraudDetection.knn;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class DistanceFunctions {

    private DistanceFunctions() {}

    public static float sqDist(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < 14; i++) { float d = a[i] - b[i]; s += d * d; }
        return s;
    }

    private static final VectorSpecies<Byte>    B64  = ByteVector.SPECIES_64;   // 8 lanes
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;   // 8 lanes


    public static int sqDistI8(byte[] q, byte[] v) {
        IntVector acc = IntVector.zero(I256);
        for (int off = 0; off < 16; off += 8) {                 
            ByteVector qb = ByteVector.fromArray(B64, q, off);
            ByteVector vb = ByteVector.fromArray(B64, v, off);
            IntVector qi = (IntVector) qb.convertShape(VectorOperators.B2I, I256, 0);
            IntVector vi = (IntVector) vb.convertShape(VectorOperators.B2I, I256, 0);
            IntVector d  = qi.sub(vi);
            acc = acc.add(d.mul(d));
        }
        return acc.reduceLanes(VectorOperators.ADD);
    }

    public static int sqDistI8Scalar(byte[] q, byte[] v) {
        int acc = 0;
        for (int k = 0; k < 16; k++) { int d = q[k] - v[k]; acc += d * d; }
        return acc;
    }
}