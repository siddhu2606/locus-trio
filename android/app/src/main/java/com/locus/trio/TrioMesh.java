package com.locus.trio;

import java.util.Locale;

/**
 * The shared picture of the mesh, as seen by ONE device (index `me`). N devices, 3 to 6.
 *   - Readings this device makes itself come from its own Bluetooth scan (it hears the others).
 *   - Readings the others make arrive inside their advertisements, so this device learns every directional reading
 *     (A hears B, B hears A, ...) without any Wi-Fi.
 * Each pair's two readings are averaged, turned into a distance, and all the distances are laid out in 2D.
 * Pure Java: no Android classes. Methods are synchronized because the HTTP server thread reads while the UI thread writes.
 */
public class TrioMesh {
    public static final long MAX_AGE = 5000;      // readings older than this are ignored
    public final int me, n;
    public double rssi1m = -59, pathLoss = 2.2;
    private final TrioMath.Window[] own;
    private final int[][] remote;
    private final long[][] remoteAt;
    private final long[] heardAt;
    private final long[] pktCount;
    private long adCounter = 0;

    public TrioMesh(int me, int n) {
        this.me = me; this.n = n;
        own = new TrioMath.Window[n];
        for (int i = 0; i < n; i++) own[i] = new TrioMath.Window();
        remote = new int[n][n]; remoteAt = new long[n][n]; heardAt = new long[n]; pktCount = new long[n];
    }

    /** This device just heard device `from` advertise, at `rssi` dBm; the packet carries that device's own readings. */
    public synchronized void onPacket(int from, int rssi, int[] reportedByFrom, long now) {
        if (from == me || from < 0 || from >= n) return;
        own[from].add(rssi, now);
        heardAt[from] = now;
        pktCount[from]++;
        for (int k = 0; k < n && k < reportedByFrom.length; k++) { remote[from][k] = reportedByFrom[k]; remoteAt[from][k] = now; }
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

    /** Payload this device should advertise right now. */
    public synchronized byte[] payload(long now) {
        int[] rep = new int[n];
        for (int k = 0; k < n; k++) {
            double m = k == me ? Double.NaN : own[k].median(now, MAX_AGE);
            rep[k] = Double.isNaN(m) ? 0 : (int) Math.round(m);
        }
        return TrioMath.encode(me, rep, (int) (adCounter++ & 0xFF));
    }

    /** Set rssi1m so the pair (i, j) reads as `knownMetres`. */
    public synchronized boolean calibrate(int i, int j, double knownMetres, long now) {
        double r = linkRssi(i, j, now);
        if (Double.isNaN(r)) return false;
        rssi1m = TrioMath.calibrate1m(r, knownMetres, pathLoss);
        return true;
    }

    public synchronized double[][] distances(long now) {
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (i == j) { d[i][j] = 0; continue; }
            double r = linkRssi(i, j, now);
            d[i][j] = Double.isNaN(r) ? Double.NaN : TrioMath.distance(r, rssi1m, pathLoss);
        }
        return d;
    }

    public synchronized TrioMath.Layout layout(long now) { return TrioMath.layout(distances(now)); }

    private static String num(double x, int dp) { return Double.isNaN(x) ? "null" : String.format(Locale.US, "%." + dp + "f", x); }

    public synchronized String toJson(long now, String device) {
        StringBuilder sb = new StringBuilder("{\"device\":\"").append(device.replace("\"", "'")).append("\",\"me\":").append(me).append(",\"n\":").append(n)
                .append(",\"rssi1m\":").append(num(rssi1m, 1)).append(",\"pathLoss\":").append(num(pathLoss, 2)).append(",\"heard\":[");
        for (int k = 0; k < n; k++) sb.append(k > 0 ? "," : "").append(k == me || heard(k, now));
        sb.append("],\"links\":[");
        double[][] d = distances(now);
        TrioMath.Layout L = TrioMath.layout(d);
        boolean first = true;
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) {
            double ab = directional(a, b, now), ba = directional(b, a, now);
            sb.append(first ? "" : ",").append("{\"a\":").append(a).append(",\"b\":").append(b)
                    .append(",\"ab\":").append(num(ab, 0)).append(",\"ba\":").append(num(ba, 0))
                    .append(",\"rssi\":").append(num(TrioMath.combine(ab, ba), 1))
                    .append(",\"d\":").append(num(d[a][b], 2))
                    .append(",\"agree\":").append(Double.isNaN(ab) || Double.isNaN(ba) ? "null" : num(Math.abs(ab - ba), 0))
                    .append(",\"resid\":").append(L == null ? "null" : num(L.resid[a][b], 2))
                    .append(",\"trust\":").append(L == null || Double.isNaN(d[a][b]) ? "null" : num(L.weight[a][b], 2)).append('}');
            first = false;
        }
        sb.append("],\"layout\":");
        if (L == null) sb.append("null");
        else {
            sb.append("{\"rms\":").append(num(L.rms, 2)).append(",\"links\":").append(L.links).append(",\"pts\":[");
            for (int k = 0; k < n; k++) sb.append(k > 0 ? "," : "").append('[').append(num(L.pts[k][0], 2)).append(',').append(num(L.pts[k][1], 2)).append(']');
            sb.append("]}");
        }
        return sb.append('}').toString();
    }
}
