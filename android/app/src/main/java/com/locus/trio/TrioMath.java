package com.locus.trio;

import java.util.Arrays;

/**
 * Pure maths for the LOCUS Trio mesh (no Android classes, so it can be unit-tested on a laptop).
 * A mesh has N devices (3 to 6): phones, laptops, ESP32 boards... anything that can advertise and scan Bluetooth LE.
 * Devices are indexed 0..N-1 and shown as letters A..F.
 */
public final class TrioMath {
    private TrioMath() { }

    public static final int MAGIC0 = 'L', MAGIC1 = 'T';
    public static final int MIN_N = 3, MAX_N = 6;

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

    /** Average the two directional readings of one pair (in dB). NaN means missing. Returns NaN if both are missing. */
    public static double combine(double ij, double ji) {
        boolean a = !Double.isNaN(ij), b = !Double.isNaN(ji);
        if (a && b) return (ij + ji) / 2.0;
        if (a) return ij;
        if (b) return ji;
        return Double.NaN;
    }

    // ---------------------------------------------------------------- packet
    /** What a device advertises: 'L','T', its index + 1, its latest reading of each of the N devices (signed dBm, 0 = unknown), a counter. Length is 4 + N bytes. */
    public static byte[] encode(int device, int[] reported, int counter) {
        int n = reported.length;
        byte[] b = new byte[4 + n];
        b[0] = MAGIC0; b[1] = MAGIC1; b[2] = (byte) (device + 1);
        for (int k = 0; k < n; k++) b[3 + k] = (byte) Math.max(-127, Math.min(0, reported[k]));
        b[3 + n] = (byte) counter;
        return b;
    }

    public static final class Packet {
        public int from;
        public int[] reported;
        public int counter;
    }

    /** Returns null when this is not a Trio packet. The mesh size is read from the length, so 7 bytes = 3 devices, 10 bytes = 6 devices. */
    public static Packet decode(byte[] d) {
        if (d == null || d.length < 4 + MIN_N || d.length > 4 + MAX_N || d[0] != MAGIC0 || d[1] != MAGIC1) return null;
        int n = d.length - 4, from = d[2] - 1;
        if (from < 0 || from >= n) return null;
        Packet p = new Packet();
        p.from = from;
        p.reported = new int[n];
        for (int k = 0; k < n; k++) p.reported[k] = d[3 + k];
        p.counter = d[3 + n] & 0xFF;
        return p;
    }

    // ---------------------------------------------------------------- window
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

    // ---------------------------------------------------------------- layout
    public static final class Layout {
        public double[][] pts;        // device positions in metres: device 0 at the origin, device 1 on +x, device 2 above the line
        public double rms;            // typical disagreement between the layout and the measured distances, metres
        public double[][] resid;      // layout distance minus measured distance for each pair (NaN = pair not measured)
        public double[][] weight;     // trust given to each pair after outlier down-weighting, 0..1
        public int links;             // pairs that were measured
    }

    /**
     * Lays N devices out in 2D from a matrix of measured distances (NaN = not measured).
     * Weighted stress minimisation, several fixed starting layouts, then two rounds of down-weighting the pairs that disagree most.
     * Needs at least 2N-3 measured pairs and two for every device; otherwise returns null.
     * With exactly 3 devices this is the exact triangle. With more devices the extra readings let a bad one be spotted and trusted less.
     */
    public static Layout layout(double[][] D) {
        int n = D.length, links = 0;
        int[] deg = new int[n];
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) if (!Double.isNaN(D[i][j])) { links++; deg[i]++; deg[j]++; }
        if (n < MIN_N || links < 2 * n - 3) return null;
        for (int d : deg) if (d < 2) return null;
        double[][] W = new double[n][n];
        double mean = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (i != j && !Double.isNaN(D[i][j])) { W[i][j] = 1; mean += D[i][j]; }
        mean /= (2.0 * links);
        double[][] best = null;
        double bestS = 1e18;
        for (int round = 0; round < 3; round++) {
            best = null; bestS = 1e18;
            for (int k = 0; k < 8; k++) {
                double[][] x = new double[n][2];
                for (int i = 0; i < n; i++) {
                    double ang = 2 * Math.PI * i / n + 0.9 * k + 0.13 * k * i * i, rad = mean * (0.6 + 0.12 * ((i + k) % 3));
                    x[i][0] = rad * Math.cos(ang); x[i][1] = rad * Math.sin(ang);
                }
                relax(x, D, W, 500);
                double s = stress(x, D, W);
                if (s < bestS) { bestS = s; best = x; }
            }
            if (round == 2) break;
            // Huber-style: pairs far from the layout lose weight
            double[] rs = new double[links];
            int c = 0;
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) if (!Double.isNaN(D[i][j])) rs[c++] = Math.abs(dist(best, i, j) - D[i][j]);
            Arrays.sort(rs);
            double k = Math.max(0.35, 1.5 * rs[rs.length / 2]);
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (i != j && !Double.isNaN(D[i][j])) { double r = Math.abs(dist(best, i, j) - D[i][j]); W[i][j] = r <= k ? 1 : k / r; }
        }
        // fix rotation, translation and mirror: device 0 at the origin, device 1 on +x, device 2 above
        double[][] x = best;
        double ox = x[0][0], oy = x[0][1];
        for (double[] p : x) { p[0] -= ox; p[1] -= oy; }
        double a = Math.atan2(x[1][1], x[1][0]), ca = Math.cos(-a), sa = Math.sin(-a);
        for (double[] p : x) { double px = p[0] * ca - p[1] * sa, py = p[0] * sa + p[1] * ca; p[0] = px; p[1] = py; }
        if (x[2][1] < 0) for (double[] p : x) p[1] = -p[1];
        Layout L = new Layout();
        L.pts = x; L.links = links;
        L.resid = new double[n][n]; L.weight = W;
        double sum = 0; int c = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (i == j || Double.isNaN(D[i][j])) { L.resid[i][j] = Double.NaN; continue; }
            L.resid[i][j] = dist(x, i, j) - D[i][j];
            if (i < j) { sum += L.resid[i][j] * L.resid[i][j]; c++; }
        }
        L.rms = Math.sqrt(sum / Math.max(1, c));
        return L;
    }

    private static double dist(double[][] x, int i, int j) { return Math.hypot(x[i][0] - x[j][0], x[i][1] - x[j][1]); }

    private static double stress(double[][] x, double[][] D, double[][] W) {
        double s = 0;
        for (int i = 0; i < x.length; i++) for (int j = i + 1; j < x.length; j++) if (W[i][j] > 0) { double r = dist(x, i, j) - D[i][j]; s += W[i][j] * r * r; }
        return s;
    }

    private static void relax(double[][] x, double[][] D, double[][] W, int iters) {
        int n = x.length;
        for (int it = 0; it < iters; it++) {
            for (int i = 0; i < n; i++) {
                double fx = 0, fy = 0, sw = 0;
                for (int j = 0; j < n; j++) {
                    if (i == j || W[i][j] <= 0) continue;
                    double dx = x[j][0] - x[i][0], dy = x[j][1] - x[i][1], d = Math.max(1e-6, Math.hypot(dx, dy));
                    double k = W[i][j] * (d - D[i][j]) / d;
                    fx += k * dx; fy += k * dy; sw += W[i][j];
                }
                if (sw > 0) { x[i][0] += 0.5 * fx / sw; x[i][1] += 0.5 * fy / sw; }
            }
        }
    }
}
