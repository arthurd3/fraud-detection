package org.fraudDetection.json;

import org.fraudDetection.server.ConnectionState;

import java.nio.ByteBuffer;

/**
 * Walker byte-a-byte do body do POST /fraud-score → state.queryVector[14].
 * Sem String/Map/regex/objeto intermediário. Schema fixo (REGRAS_DE_DETECCAO.md).
 */
public final class FraudRequestParser {

    public static final int PARSE_OK  = 0;
    public static final int PARSE_BAD = -1;

    // normalization.json (constantes fixas)
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

        // ---- ranges dos objetos de 1º nível ----
        int trS = objStart(b, from, to, K_TRANSACTION); if (trS < 0) return PARSE_BAD;
        int trE = matchBrace(b, trS, to);
        int cuS = objStart(b, from, to, K_CUSTOMER);    if (cuS < 0) return PARSE_BAD;
        int cuE = matchBrace(b, cuS, to);
        int meS = objStart(b, from, to, K_MERCHANT);    if (meS < 0) return PARSE_BAD;
        int meE = matchBrace(b, meS, to);
        int teS = objStart(b, from, to, K_TERMINAL);    if (teS < 0) return PARSE_BAD;
        int teE = matchBrace(b, teS, to);

        // ---- transaction ----
        double amount       = num(b, valPos(b, trS, trE, K_AMOUNT));
        double installments = num(b, valPos(b, trS, trE, K_INSTALLMENTS));
        int reqAt = strPos(b, trS, trE, K_REQUESTED_AT);          // índice da 1ª aspa do valor
        // ---- customer ----
        double avgAmount    = num(b, valPos(b, cuS, cuE, K_AVG_AMOUNT));
        double txCount24h   = num(b, valPos(b, cuS, cuE, K_TX_COUNT_24H));
        int kmA = valPos(b, cuS, cuE, K_KNOWN_MERCHANTS);          // '[' do array
        int kmB = matchBracket(b, kmA, cuE);
        // ---- merchant ----
        int midA = strPos(b, meS, meE, K_ID) + 1;                  // 1º char do id (após aspa)
        int midB = strEnd(b, midA);
        int mccA = strPos(b, meS, meE, K_MCC) + 1;
        int mccB = strEnd(b, mccA);
        double merchAvg     = num(b, valPos(b, meS, meE, K_AVG_AMOUNT));
        // ---- terminal ----
        boolean isOnline    = bool(b, valPos(b, teS, teE, K_IS_ONLINE));
        boolean cardPresent = bool(b, valPos(b, teS, teE, K_CARD_PRESENT));
        double kmFromHome   = num(b, valPos(b, teS, teE, K_KM_FROM_HOME));

        // ---- datas ----
        long reqEpoch = isoEpochSec(b, reqAt + 1);                 // +1: pula a aspa
        int  hour     = twoDigit(b, reqAt + 1 + 11);               // chars[11..12] = HH
        int  dow      = dowMon0(isoCivilDays(b, reqAt + 1));        // seg=0..dom=6

        // ---- last_transaction (null | objeto) ----
        int ltVal = valPos(b, from, to, K_LAST_TRANSACTION);       // 'n' (null) ou '{'
        double minNorm, kmLastNorm;
        if (ltVal < 0) return PARSE_BAD;
        boolean lastNull = (b.get(ltVal) == 'n');
        if (lastNull) {                                            // null
            minNorm = -1; kmLastNorm = -1;
        } else {                                                   // objeto { timestamp, km_from_current }
            int ltE  = matchBrace(b, ltVal, to);
            int tsA  = strPos(b, ltVal, ltE, K_TIMESTAMP);
            long lastEpoch = isoEpochSec(b, tsA + 1);
            double minutes = (reqEpoch - lastEpoch) / 60.0;
            minNorm    = clamp(minutes / MAX_MINUTES);
            double kmc = num(b, valPos(b, ltVal, ltE, K_KM_FROM_CURRENT));
            kmLastNorm = clamp(kmc / MAX_KM);
        }

        // ---- monta o vetor ----
        // Onda 7 v2: round4 EM DOUBLE antes do cast p/ float — replica
        // main.c: entries[i].vec[j] = round4(normalize(...)) (linha ~774,
        // todas as 14 dims). round4(x) = round(x*10000)/10000 (round-half-up
        // == C round() p/ as dims não-negativas; -1 sentinela é exato:
        // Math.round(-10000.0) = -10000 → -1.0). Dims 5/6 sentinela (-1)
        // ficam LITERAIS -1f (NÃO round4 — armadilha (long)(-1*10000+0.5);
        // round4(-1) == -1 de qualquer forma, mas seguimos o plano à risca).
        v[0]  = r4(clamp(amount / MAX_AMOUNT));
        v[1]  = r4(clamp(installments / MAX_INSTALLMENTS));
        v[2]  = r4(clamp((amount / avgAmount) / AMOUNT_VS_AVG_RATIO));
        v[3]  = r4(hour / 23.0);
        v[4]  = r4(dow / 6.0);
        v[5]  = lastNull ? -1f : r4(minNorm);
        v[6]  = lastNull ? -1f : r4(kmLastNorm);
        v[7]  = r4(clamp(kmFromHome / MAX_KM));
        v[8]  = r4(clamp(txCount24h / MAX_TX_24H));
        v[9]  = isOnline    ? 1f : 0f;                              // 0/1 exatos
        v[10] = cardPresent ? 1f : 0f;
        v[11] = inArray(b, kmA, kmB, midA, midB) ? 0f : 1f;        // invertido; 0/1 exatos
        v[12] = r4(mccRisk(b, mccA, mccB));
        v[13] = r4(clamp(merchAvg / MAX_MERCH_AVG));
        return PARSE_OK;
    }

    // ===================== helpers =====================

    private static double clamp(double x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }

    /** C round4 then cast to float: (float)(round(x*10000)/10000).
     *  Done in DOUBLE first so queryVector holds exactly k/10000 — the EXACT
     *  double rerank then recovers k bit-identically to main.c. */
    private static float r4(double x) {
        return (float) (Math.round(x * 10000.0) / 10000.0);
    }

    // ---- chaves (bytes, lowercase exato) ----
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
    private static byte[] b(String s) {
        byte[] r = new byte[s.length()];
        for (int i = 0; i < r.length; i++) r[i] = (byte) s.charAt(i);
        return r;
    }

    /** Acha "key" exato (token entre aspas) em [from,to). Retorna idx da 1ª aspa, ou -1. */
    private static int findKeyExact(ByteBuffer b, int from, int to, byte[] key) {
        for (int i = from; i + key.length + 1 < to; i++) {
            if (b.get(i) != '"') continue;
            int j = 0;
            while (j < key.length && b.get(i + 1 + j) == key[j]) j++;
            if (j == key.length && b.get(i + 1 + key.length) == '"') return i;
        }
        return -1;
    }

    /** Posição do valor (1º não-ws depois do ':') da key em [from,to). */
    private static int valPos(ByteBuffer b, int from, int to, byte[] key) {
        int k = findKeyExact(b, from, to, key);
        if (k < 0) return -1;
        int i = k + key.length + 2;            // pula "key"
        while (b.get(i) != ':') i++;
        return nextNonWs(b, i + 1, to);
    }

    /** Idx do '{' do objeto da key (objStart). */
    private static int objStart(ByteBuffer b, int from, int to, byte[] key) {
        int p = valPos(b, from, to, key);
        return (p >= 0 && b.get(p) == '{') ? p : -1;
    }

    /** Idx da 1ª aspa do valor-string da key. */
    private static int strPos(ByteBuffer b, int from, int to, byte[] key) {
        return valPos(b, from, to, key);       // valor de string começa na própria aspa
    }

    private static int strEnd(ByteBuffer b, int afterQuote) {      // idx da aspa de fechamento
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

    /** Dado idx de '{', retorna idx do '}' casado. */
    private static int matchBrace(ByteBuffer b, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            byte c = b.get(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return to - 1;
    }

    /** Dado idx de '[', retorna idx do ']' casado. */
    private static int matchBracket(ByteBuffer b, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            byte c = b.get(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return to - 1;
    }

    /** Número (double) começando em pos: sinal, inteiro, fração. Sem expoente. */
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

    /** id (idA..idB) está entre os elementos do array [arrA..arrB]? (token entre aspas exato) */
    private static boolean inArray(ByteBuffer b, int arrA, int arrB, int idA, int idB) {
        int len = idB - idA;
        for (int i = arrA; i < arrB; i++) {
            if (b.get(i) != '"') continue;
            int s = i + 1, j = 0;
            while (j < len && b.get(s + j) == b.get(idA + j)) j++;
            if (j == len && b.get(s + len) == '"') return true;
            i = strEnd(b, s);                  // pula pro fim dessa string
        }
        return false;
    }

    // mcc_risk.json (10 códigos; default 0.5). Compara os 4 bytes do mcc.
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

    // ---- datas ISO-8601 "YYYY-MM-DDTHH:MM:SSZ" (pos = 1º char, após a aspa) ----
    private static int dig(ByteBuffer b, int p) { return b.get(p) - '0'; }
    private static int twoDigit(ByteBuffer b, int p) { return dig(b, p) * 10 + dig(b, p + 1); }
    private static int fourDigit(ByteBuffer b, int p) {
        return dig(b,p)*1000 + dig(b,p+1)*100 + dig(b,p+2)*10 + dig(b,p+3);
    }

    /** Dias civis desde 1970-01-01 (algoritmo de Howard Hinnant). */
    private static long isoCivilDays(ByteBuffer b, int p) {
        int y = fourDigit(b, p);          // [0..3]
        int m = twoDigit(b, p + 5);       // [5..6]
        int d = twoDigit(b, p + 8);       // [8..9]
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
    /** 1970-01-01 foi quinta. Converte pra seg=0..dom=6. */
    private static int dowMon0(long days) { return (int) Math.floorMod(days + 3, 7); }

    private static long isoEpochSec(ByteBuffer b, int p) {
        long days = isoCivilDays(b, p);
        int hh = twoDigit(b, p + 11), mm = twoDigit(b, p + 14), ss = twoDigit(b, p + 17);
        return days * 86400L + hh * 3600L + mm * 60L + ss;
    }
}