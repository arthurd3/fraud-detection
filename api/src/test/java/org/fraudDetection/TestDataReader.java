package org.fraudDetection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TestDataReader {
    private final String all;
    private int pos;

    TestDataReader(String path) throws IOException {
        all = Files.readString(Path.of(path));      
        pos = all.indexOf("\"entries\"");
    }

    static final class Entry { String body; boolean expected; double expectedFraudScore; }

    Entry next() {
        int r = all.indexOf("\"request\"", pos);
        if (r < 0) return null;
        int objStart = all.indexOf('{', r);
        int objEnd   = matchBrace(all, objStart);
        int ea       = all.indexOf("\"expected_approved\"", objEnd);
        int colon    = all.indexOf(':', ea);
        boolean exp  = all.regionMatches(true, firstNonWs(all, colon + 1), "true", 0, 4);
        // Onda 7 v2 (additive): parse "expected_fraud_score" (∈ {0,0.2,0.4,0.6,0.8,1}).
        int fs       = all.indexOf("\"expected_fraud_score\"", objEnd);
        int fsColon  = all.indexOf(':', fs);
        double score = parseNum(all, firstNonWs(all, fsColon + 1));
        Entry e = new Entry();
        e.body     = all.substring(objStart, objEnd + 1);
        e.expected = exp;
        e.expectedFraudScore = score;
        pos = objEnd + 1;
        return e;
    }

    private static double parseNum(String s, int i) {
        int j = i;
        while (j < s.length()) {
            char c = s.charAt(j);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') j++; else break;
        }
        return Double.parseDouble(s.substring(i, j));
    }

    private static int firstNonWs(String s, int i) {
        while (i < s.length()) { char c = s.charAt(i); if (c!=' '&&c!='\t'&&c!='\r'&&c!='\n') return i; i++; }
        return i;
    }
    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return s.length() - 1;
    }
}