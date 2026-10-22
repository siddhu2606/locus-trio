package com.locus.trio;

import java.util.Arrays;

/**
 * Pure maths for the LOCUS Trio mesh (no Android classes, so it can be unit-tested on a laptop).
 * Three phones A, B, C are indexed 0, 1, 2. Pairs are indexed AB = 0, AC = 1, BC = 2.
 */
public final class TrioMath {
    private TrioMath() { }

    public static final int MAGIC0 = 'L', MAGIC1 = 'T';

    /** Distance in metres from an RSSI reading, with the log-distance path-loss model. rssi1m is the reading at 1 m. */
    public static double distance(double rssiDbm, double rssi1m, double n) {
        return Math.pow(10, (rssi1m - rssiDbm) / (10 * n));
    }

    /** Inverse of distance(): the RSSI expected at d metres. */
    public static double rssiAt(double d, double rssi1m, double n) {
        return rssi1m - 10 * n * Math.log10(Math.max(d, 0.05));
    }

    /** The rssi1m that makes a measured RSSI correspond to a known distance. */
    public static double calibrate1m(double rssiDbm, double knownMetres, double n) {
        return rssiDbm + 10 * n * Math.log10(Math.max(knownMetres, 0.05));
    }

    public static int pair(int i, int j) {
        int a = Math.min(i, j), b = Math.max(i, j);
        return a == 0 ? (b == 1 ? 0 : 1) : 2;
    }

    public static int pairA(int p) { return p == 2 ? 1 : 0; }

    public static int pairB(int p) { return p == 0 ? 1 : 2; }

    /** Average the two directional readings of one pair (in dB). NaN means missing. Returns NaN if both are missing. */
    public static double combine(double ij, double ji) {
        boolean a = !Double.isNaN(ij), b = !Double.isNaN(ji);
        if (a && b) return (ij + ji) / 2.0;
        if (a) return ij;
        if (b) return ji;
        return Double.NaN;
    }

    /** Triangle laid out from its three sides: A at the origin, B on the +x axis, C above. Mirror image is not knowable from distances alone. */
    public static final class Tri {
        public final double[][] pts = new double[3][2];
        public boolean valid;      // false when the three distances break the triangle inequality
        public double gap;         // metres by which the longest side exceeds the other two together (0 when valid)
    }

    public static Tri triangle(double dAB, double dAC, double dBC) {
        Tri t = new Tri();
        dAB = Math.max(dAB, 0.05);
        double x = (dAB * dAB + dAC * dAC - dBC * dBC) / (2 * dAB);
        double y2 = dAC * dAC - x * x;
        double longest = Math.max(dAB, Math.max(dAC, dBC));
        double gap = longest - (dAB + dAC + dBC - longest);
        t.gap = Math.max(0, gap);
        t.valid = y2 >= 0 && gap <= 0;
        t.pts[0][0] = 0; t.pts[0][1] = 0;
        t.pts[1][0] = dAB; t.pts[1][1] = 0;
        t.pts[2][0] = x; t.pts[2][1] = Math.sqrt(Math.max(0, y2));
        return t;
    }

    /** Advertisement payload: 'L','T', phone index + 1, this phone's reading of A, B, C (signed dBm, 0 = unknown), a counter. */
    public static byte[] encode(int phone, int[] reported, int counter) {
        byte[] b = new byte[7];
        b[0] = MAGIC0; b[1] = MAGIC1; b[2] = (byte) (phone + 1);
        for (int k = 0; k < 3; k++) b[3 + k] = (byte) Math.max(-127, Math.min(0, reported[k]));
        b[6] = (byte) counter;
        return b;
    }

    /** Returns {phone, readingOfA, readingOfB, readingOfC, counter} or null when this is not a Trio packet. */
    public static int[] decode(byte[] d) {
        if (d == null || d.length < 7 || d[0] != MAGIC0 || d[1] != MAGIC1) return null;
        int phone = d[2] - 1;
        if (phone < 0 || phone > 2) return null;
        return new int[]{phone, d[3], d[4], d[5], d[6] & 0xFF};
    }

    /** Short sliding window of RSSI samples. The median resists the occasional wild reading. */
    public static final class Window {
        private final long[] t = new long[96];
        private final double[] v = new double[96];
        private int head = 0, size = 0;

        public void add(double rssi, long now) {
            t[head] = now; v[head] = rssi;
            head = (head + 1) % t.length;
            if (size < t.length) size++;
        }

        public int count(long now, long maxAgeMs) {
            int c = 0;
            for (int k = 0; k < size; k++) if (now - t[k] <= maxAgeMs) c++;
            return c;
        }

        public double median(long now, long maxAgeMs) {
            double[] tmp = new double[size];
            int c = 0;
            for (int k = 0; k < size; k++) if (now - t[k] <= maxAgeMs) tmp[c++] = v[k];
            if (c == 0) return Double.NaN;
            Arrays.sort(tmp, 0, c);
            return c % 2 == 1 ? tmp[c / 2] : (tmp[c / 2 - 1] + tmp[c / 2]) / 2.0;
        }
    }
}
