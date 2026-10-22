package com.locus.trio;

import java.util.Locale;

/**
 * The shared picture of the three phones, as seen by ONE phone (index `me`).
 *   - Readings this phone makes itself come from its own Bluetooth scan (it hears the other two phones).
 *   - Readings the other two phones make arrive inside their advertisements, so this phone learns all six directional readings
 *     (A hears B, B hears A, A hears C, C hears A, B hears C, C hears B) without any Wi-Fi.
 * Each pair's two readings are averaged, turned into a distance, and the three distances are laid out as a triangle.
 * Pure Java: no Android classes. Methods are synchronized because the HTTP server thread reads while the UI thread writes.
 */
public class TrioMesh {
    public static final long MAX_AGE = 5000;      // readings older than this are ignored
    public final int me;
    public double rssi1m = -59, n = 2.2;
    private final TrioMath.Window[] own = {new TrioMath.Window(), new TrioMath.Window(), new TrioMath.Window()};
    private final int[][] remote = new int[3][3];
    private final long[][] remoteAt = new long[3][3];
    private final long[] heardAt = new long[3];
    private final long[] pktCount = new long[3];
    private long adCounter = 0;

    public TrioMesh(int me) { this.me = me; }

    /** This phone just heard phone `from` advertise, at `rssi` dBm; the packet carries that phone's own readings. */
    public synchronized void onPacket(int from, int rssi, int[] reportedByFrom, long now) {
        if (from == me || from < 0 || from > 2) return;
        own[from].add(rssi, now);
        heardAt[from] = now;
        pktCount[from]++;
        for (int k = 0; k < 3; k++) { remote[from][k] = reportedByFrom[k]; remoteAt[from][k] = now; }
    }

    public synchronized boolean heard(int p, long now) { return p != me && heardAt[p] > 0 && now - heardAt[p] < MAX_AGE; }

    public synchronized long packets(int p) { return pktCount[p]; }

    /** Reading made by `from` of `to`'s signal in dBm, or NaN. */
    public synchronized double directional(int from, int to, long now) {
        if (from == to) return Double.NaN;
        if (from == me) return own[to].median(now, MAX_AGE);
        if (remote[from][to] != 0 && now - remoteAt[from][to] <= MAX_AGE) return remote[from][to];
        return Double.NaN;
    }

    public synchronized double linkRssi(int i, int j, long now) { return TrioMath.combine(directional(i, j, now), directional(j, i, now)); }

    /** Payload this phone should advertise right now. */
    public synchronized byte[] payload(long now) {
        int[] rep = new int[3];
        for (int k = 0; k < 3; k++) {
            double m = k == me ? Double.NaN : own[k].median(now, MAX_AGE);
            rep[k] = Double.isNaN(m) ? 0 : (int) Math.round(m);
        }
        return TrioMath.encode(me, rep, (int) (adCounter++ & 0xFF));
    }

    /** Set rssi1m so the pair (i, j) reads as `knownMetres`. */
    public synchronized boolean calibrate(int i, int j, double knownMetres, long now) {
        double r = linkRssi(i, j, now);
        if (Double.isNaN(r)) return false;
        rssi1m = TrioMath.calibrate1m(r, knownMetres, n);
        return true;
    }

    public synchronized double[] distances(long now) {
        double[] d = new double[3];
        for (int p = 0; p < 3; p++) {
            double r = linkRssi(TrioMath.pairA(p), TrioMath.pairB(p), now);
            d[p] = Double.isNaN(r) ? Double.NaN : TrioMath.distance(r, rssi1m, n);
        }
        return d;
    }

    public synchronized TrioMath.Tri triangle(long now) {
        double[] d = distances(now);
        for (double x : d) if (Double.isNaN(x)) return null;
        return TrioMath.triangle(d[0], d[1], d[2]);
    }

    private static String num(double x, int dp) { return Double.isNaN(x) ? "null" : String.format(Locale.US, "%." + dp + "f", x); }

    public synchronized String toJson(long now, String device) {
        StringBuilder sb = new StringBuilder("{\"device\":\"").append(device.replace("\"", "'")).append("\",\"me\":").append(me)
                .append(",\"rssi1m\":").append(num(rssi1m, 1)).append(",\"n\":").append(num(n, 2)).append(",\"heard\":[");
        for (int k = 0; k < 3; k++) sb.append(k > 0 ? "," : "").append(heard(k, now));
        sb.append("],\"links\":[");
        double[] d = distances(now);
        for (int p = 0; p < 3; p++) {
            int a = TrioMath.pairA(p), b = TrioMath.pairB(p);
            double ab = directional(a, b, now), ba = directional(b, a, now);
            sb.append(p > 0 ? "," : "").append("{\"a\":").append(a).append(",\"b\":").append(b)
                    .append(",\"ab\":").append(num(ab, 0)).append(",\"ba\":").append(num(ba, 0))
                    .append(",\"rssi\":").append(num(TrioMath.combine(ab, ba), 1))
                    .append(",\"d\":").append(num(d[p], 2))
                    .append(",\"agree\":").append(Double.isNaN(ab) || Double.isNaN(ba) ? "null" : num(Math.abs(ab - ba), 0)).append('}');
        }
        sb.append("],\"tri\":");
        TrioMath.Tri t = triangle(now);
        if (t == null) sb.append("null");
        else {
            sb.append("{\"valid\":").append(t.valid).append(",\"gap\":").append(num(t.gap, 2)).append(",\"pts\":[");
            for (int k = 0; k < 3; k++) sb.append(k > 0 ? "," : "").append('[').append(num(t.pts[k][0], 2)).append(',').append(num(t.pts[k][1], 2)).append(']');
            sb.append("]}");
        }
        return sb.append('}').toString();
    }
}
