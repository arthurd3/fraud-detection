package org.fraudDetection.json;

import org.fraudDetection.server.ConnectionState;

import java.nio.ByteBuffer;

/**
 * Walker byte-a-byte do body do POST /fraud-score → state.queryVector[14].
 * Sem String/Map/regex/objeto intermediário. Schema fixo (REGRAS_DE_DETECCAO.md).
 *
 * <p>Onda 17 (ships the Onda 15 design): replaced the O(bodySize × 14)
 * {@code findKeyExact} pattern with a single-pass {@link #indexKeys} that
 * locates every key in a JSON range in ONE scan. Top-level scope has 5 keys,
 * each sub-object scope has 2-3 keys, so a full request now uses 6 single-pass
 * index scans instead of ~17 redundant full-range walks. G2 ExactAgree 0-div
 * over 54 100 proves semantic equivalence; calib results were inconclusive
 * under noisy host. Shipped to Mac Mini preview as the ground-truth arbiter.
 */
public final class FraudRequestParser {

    public static final int PARSE_OK  = 0;
    public static final int PARSE_BAD = -1;

    private static final double MAX_AMOUNT = 10000, MAX_INSTALLMENTS = 12,
            AMOUNT_VS_AVG_RATIO = 10, MAX_MINUTES = 1440, MAX_KM = 1000,
            MAX_TX_24H = 20, MAX_MERCH_AVG = 10000;

    private FraudRequestParser() {}

    public static int parse(ConnectionState s) {
        ByteBuffer b = s.readBuffer;
        int from = s.bodyOffset;
        int to   = s.bodyOffset + s.contentLength;
        float[] v = s.queryVector;
        if (from < 0 || to > b.position() || from >= to) return PARSE_BAD;

        int[] off = s.keyOffsets;

        indexKeys(b, from, to, TOP_KEYS, off);
        int trK = off[0], cuK = off[1], meK = off[2], teK = off[3], ltK = off[4];
        if ((trK | cuK | meK | teK | ltK) < 0) return PARSE_BAD;

        int trS = afterKeyToObj(b, trK, K_TRANSACTION.length, to, (byte) '{');
        if (trS < 0) return PARSE_BAD;
        int trE = matchBrace(b, trS, to);
        int cuS = afterKeyToObj(b, cuK, K_CUSTOMER.length, to, (byte) '{');
        if (cuS < 0) return PARSE_BAD;
        int cuE = matchBrace(b, cuS, to);
        int meS = afterKeyToObj(b, meK, K_MERCHANT.length, to, (byte) '{');
        if (meS < 0) return PARSE_BAD;
        int meE = matchBrace(b, meS, to);
        int teS = afterKeyToObj(b, teK, K_TERMINAL.length, to, (byte) '{');
        if (teS < 0) return PARSE_BAD;
        int teE = matchBrace(b, teS, to);

        indexKeys(b, trS, trE, TR_KEYS, off);
        double amount       = num(b, valAfterColon(b, off[0], K_AMOUNT.length, trE));
        double installments = num(b, valAfterColon(b, off[1], K_INSTALLMENTS.length, trE));
        int reqAt           = valAfterColon(b, off[2], K_REQUESTED_AT.length, trE);

        indexKeys(b, cuS, cuE, CU_KEYS, off);
        double avgAmount    = num(b, valAfterColon(b, off[0], K_AVG_AMOUNT.length, cuE));
        double txCount24h   = num(b, valAfterColon(b, off[1], K_TX_COUNT_24H.length, cuE));
        int kmA             = valAfterColon(b, off[2], K_KNOWN_MERCHANTS.length, cuE);
        int kmB             = matchBracket(b, kmA, cuE);

        indexKeys(b, meS, meE, ME_KEYS, off);
        int midA            = valAfterColon(b, off[0], K_ID.length, meE) + 1;
        int midB            = strEnd(b, midA);
        int mccA            = valAfterColon(b, off[1], K_MCC.length, meE) + 1;
        int mccB            = strEnd(b, mccA);
        double merchAvg     = num(b, valAfterColon(b, off[2], K_AVG_AMOUNT.length, meE));

        indexKeys(b, teS, teE, TE_KEYS, off);
        boolean isOnline    = bool(b, valAfterColon(b, off[0], K_IS_ONLINE.length, teE));
        boolean cardPresent = bool(b, valAfterColon(b, off[1], K_CARD_PRESENT.length, teE));
        double kmFromHome   = num(b, valAfterColon(b, off[2], K_KM_FROM_HOME.length, teE));

        long reqEpoch = isoEpochSec(b, reqAt + 1);
        int  hour     = twoDigit(b, reqAt + 1 + 11);
        int  dow      = dowMon0(isoCivilDays(b, reqAt + 1));

        int ltVal = valAfterColon(b, ltK, K_LAST_TRANSACTION.length, to);
        if (ltVal < 0) return PARSE_BAD;
        double minNorm, kmLastNorm;
        boolean lastNull = (b.get(ltVal) == 'n');
        if (lastNull) {
            minNorm = -1; kmLastNorm = -1;
        } else {
            int ltE  = matchBrace(b, ltVal, to);
            indexKeys(b, ltVal, ltE, LT_KEYS, off);
            int tsValPos  = valAfterColon(b, off[0], K_TIMESTAMP.length, ltE);
            long lastEpoch = isoEpochSec(b, tsValPos + 1);
            double minutes = (reqEpoch - lastEpoch) / 60.0;
            minNorm    = clamp(minutes / MAX_MINUTES);
            double kmc = num(b, valAfterColon(b, off[1], K_KM_FROM_CURRENT.length, ltE));
            kmLastNorm = clamp(kmc / MAX_KM);
        }

        v[0]  = r4(clamp(amount / MAX_AMOUNT));
        v[1]  = r4(clamp(installments / MAX_INSTALLMENTS));
        v[2]  = r4(clamp((amount / avgAmount) / AMOUNT_VS_AVG_RATIO));
        v[3]  = r4(hour / 23.0);
        v[4]  = r4(dow / 6.0);
        v[5]  = lastNull ? -1f : r4(minNorm);
        v[6]  = lastNull ? -1f : r4(kmLastNorm);
        v[7]  = r4(clamp(kmFromHome / MAX_KM));
        v[8]  = r4(clamp(txCount24h / MAX_TX_24H));
        v[9]  = isOnline    ? 1f : 0f;
        v[10] = cardPresent ? 1f : 0f;
        v[11] = inArray(b, kmA, kmB, midA, midB) ? 0f : 1f;
        v[12] = r4(mccRisk(b, mccA, mccB));
        v[13] = r4(clamp(merchAvg / MAX_MERCH_AVG));
        return PARSE_OK;
    }

    private static double clamp(double x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }
    private static float r4(double x) { return (float) (Math.round(x * 10000.0) / 10000.0); }

    private static final byte[] K_TRANSACTION     = b("transaction");
    private static final byte[] K_CUSTOMER        = b("customer");
    private static final byte[] K_MERCHANT        = b("merchant");
    private static final byte[] K_TERMINAL        = b("terminal");
    private static final byte[] K_LAST_TRANSACTION= b("last_transaction");
    private static final byte[] K_AMOUNT          = b("amount");
    private static final byte[] K_INSTALLMENTS    = b("installments");
    private static final byte[] K_REQUESTED_AT    = b("requested_at");
    private static final byte[] K_AVG_AMOUNT      = b("avg_amount");
    private static final byte[] K_TX_COUNT_24H    = b("tx_count_24h");
    private static final byte[] K_KNOWN_MERCHANTS = b("known_merchants");
    private static final byte[] K_ID              = b("id");
    private static final byte[] K_MCC             = b("mcc");
    private static final byte[] K_IS_ONLINE       = b("is_online");
    private static final byte[] K_CARD_PRESENT    = b("card_present");
    private static final byte[] K_KM_FROM_HOME    = b("km_from_home");
    private static final byte[] K_TIMESTAMP       = b("timestamp");
    private static final byte[] K_KM_FROM_CURRENT = b("km_from_current");

    private static final byte[][] TOP_KEYS = { K_TRANSACTION, K_CUSTOMER, K_MERCHANT, K_TERMINAL, K_LAST_TRANSACTION };
    private static final byte[][] TR_KEYS = { K_AMOUNT, K_INSTALLMENTS, K_REQUESTED_AT };
    private static final byte[][] CU_KEYS = { K_AVG_AMOUNT, K_TX_COUNT_24H, K_KNOWN_MERCHANTS };
    private static final byte[][] ME_KEYS = { K_ID, K_MCC, K_AVG_AMOUNT };
    private static final byte[][] TE_KEYS = { K_IS_ONLINE, K_CARD_PRESENT, K_KM_FROM_HOME };
    private static final byte[][] LT_KEYS = { K_TIMESTAMP, K_KM_FROM_CURRENT };

    private static byte[] b(String s) {
        byte[] r = new byte[s.length()];
        for (int i = 0; i < r.length; i++) r[i] = (byte) s.charAt(i);
        return r;
    }

    private static void indexKeys(ByteBuffer b, int from, int to, byte[][] keys, int[] out) {
        int n = keys.length;
        for (int k = 0; k < n; k++) out[k] = -1;
        int found = 0;
        int max = to - 1;
        for (int i = from; i < max && found < n; i++) {
            if (b.get(i) != '"') continue;
            for (int k = 0; k < n; k++) {
                if (out[k] >= 0) continue;
                byte[] key = keys[k];
                int klen = key.length;
                if (i + klen + 1 >= to) continue;
                int j = 0;
                while (j < klen && b.get(i + 1 + j) == key[j]) j++;
                if (j == klen && b.get(i + 1 + klen) == '"') {
                    out[k] = i;
                    found++;
                    break;
                }
            }
        }
    }

    private static int valAfterColon(ByteBuffer b, int openQuote, int keyLen, int to) {
        if (openQuote < 0) return -1;
        int i = openQuote + keyLen + 2;
        while (i < to && b.get(i) != ':') i++;
        if (i >= to) return -1;
        return nextNonWs(b, i + 1, to);
    }

    private static int afterKeyToObj(ByteBuffer b, int openQuote, int keyLen, int to, byte expected) {
        int p = valAfterColon(b, openQuote, keyLen, to);
        return (p >= 0 && b.get(p) == expected) ? p : -1;
    }

    @SuppressWarnings("unused")
    private static int findKeyExact(ByteBuffer b, int from, int to, byte[] key) {
        for (int i = from; i + key.length + 1 < to; i++) {
            if (b.get(i) != '"') continue;
            int j = 0;
            while (j < key.length && b.get(i + 1 + j) == key[j]) j++;
            if (j == key.length && b.get(i + 1 + key.length) == '"') return i;
        }
        return -1;
    }

    private static int strEnd(ByteBuffer b, int afterQuote) {
        int i = afterQuote;
        while (b.get(i) != '"') i++;
        return i;
    }

    private static int nextNonWs(ByteBuffer b, int i, int to) {
        while (i < to) {
            byte c = b.get(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return i;
            i++;
        }
        return to - 1;
    }

    private static int matchBrace(ByteBuffer b, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            byte c = b.get(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return to - 1;
    }

    private static int matchBracket(ByteBuffer b, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            byte c = b.get(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return to - 1;
    }

    private static double num(ByteBuffer b, int pos) {
        int i = pos;
        boolean neg = b.get(i) == '-';
        if (neg) i++;
        double val = 0;
        for (byte c; (c = b.get(i)) >= '0' && c <= '9'; i++) val = val * 10 + (c - '0');
        if (b.get(i) == '.') {
            i++;
            double sc = 0.1;
            for (byte c; (c = b.get(i)) >= '0' && c <= '9'; i++) { val += (c - '0') * sc; sc *= 0.1; }
        }
        return neg ? -val : val;
    }

    private static boolean bool(ByteBuffer b, int pos) { return b.get(pos) == 't'; }

    private static boolean inArray(ByteBuffer b, int arrA, int arrB, int idA, int idB) {
        int len = idB - idA;
        for (int i = arrA; i < arrB; i++) {
            if (b.get(i) != '"') continue;
            int s = i + 1, j = 0;
            while (j < len && b.get(s + j) == b.get(idA + j)) j++;
            if (j == len && b.get(s + len) == '"') return true;
            i = strEnd(b, s);
        }
        return false;
    }

    private static final byte[][] MCC = {
        b("5411"), b("5812"), b("5912"), b("5944"), b("7801"),
        b("7802"), b("7995"), b("4511"), b("5311"), b("5999")
    };
    private static final double[] MCC_R = {0.15,0.30,0.20,0.45,0.80,0.75,0.85,0.35,0.25,0.50};
    private static double mccRisk(ByteBuffer b, int a, int end) {
        int len = end - a;
        for (int m = 0; m < MCC.length; m++) {
            if (MCC[m].length != len) continue;
            int j = 0;
            while (j < len && b.get(a + j) == MCC[m][j]) j++;
            if (j == len) return MCC_R[m];
        }
        return 0.5;
    }

    private static int dig(ByteBuffer b, int p) { return b.get(p) - '0'; }
    private static int twoDigit(ByteBuffer b, int p) { return dig(b, p) * 10 + dig(b, p + 1); }
    private static int fourDigit(ByteBuffer b, int p) {
        return dig(b,p)*1000 + dig(b,p+1)*100 + dig(b,p+2)*10 + dig(b,p+3);
    }

    private static long isoCivilDays(ByteBuffer b, int p) {
        int y = fourDigit(b, p);
        int m = twoDigit(b, p + 5);
        int d = twoDigit(b, p + 8);
        return civilToDays(y, m, d);
    }
    private static long civilToDays(int y, int m, int d) {
        y -= (m <= 2) ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
    private static int dowMon0(long days) { return (int) Math.floorMod(days + 3, 7); }

    private static long isoEpochSec(ByteBuffer b, int p) {
        long days = isoCivilDays(b, p);
        int hh = twoDigit(b, p + 11), mm = twoDigit(b, p + 14), ss = twoDigit(b, p + 17);
        return days * 86400L + hh * 3600L + mm * 60L + ss;
    }
}
