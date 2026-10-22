import com.locus.trio.TrioMath;
import com.locus.trio.TrioMesh;

/** Laptop test of the Trio maths. Run: tests/run_tests.sh (needs only a JDK). Exits non-zero on any failure. */
public class TrioTest {
    static int fails = 0, checks = 0;

    static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("FAIL  " + what); } else System.out.println("ok    " + what); }

    static boolean near(double a, double b, double tol) { return Math.abs(a - b) <= tol; }

    static double[][] dists(double[][] p) {
        int n = p.length; double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) d[i][j] = Math.hypot(p[i][0] - p[j][0], p[i][1] - p[j][1]);
        return d;
    }

    /** Mean absolute difference between all pair distances of two layouts: 0 means the same shape (rotation and mirror do not matter). */
    static double shapeError(double[][] a, double[][] truth) {
        double[][] da = dists(a), dt = dists(truth); double s = 0; int c = 0;
        for (int i = 0; i < a.length; i++) for (int j = i + 1; j < a.length; j++) { s += Math.abs(da[i][j] - dt[i][j]); c++; }
        return s / c;
    }

    public static void main(String[] args) {
        // 1. path-loss round trip and calibration
        double r = TrioMath.rssiAt(3.0, -59, 2.2);
        ok(near(TrioMath.distance(r, -59, 2.2), 3.0, 1e-9), "distance(rssiAt(3 m)) == 3 m");
        ok(near(TrioMath.calibrate1m(r, 3.0, 2.2), -59, 1e-9), "calibrate1m recovers the 1 m reference");

        // 2. packets: 3-device and 6-device sizes, old beacon packets and bad ids rejected
        byte[] p3 = TrioMath.encode(2, new int[]{-61, -70, 0}, 300);
        TrioMath.Packet k3 = TrioMath.decode(p3);
        ok(p3.length == 7 && k3 != null && k3.from == 2 && k3.reported.length == 3 && k3.reported[0] == -61 && k3.reported[1] == -70 && k3.counter == (300 & 0xFF), "3-device packet is 7 bytes and round-trips");
        byte[] p6 = TrioMath.encode(5, new int[]{-50, -51, -52, -53, -54, 0}, 9);
        TrioMath.Packet k6 = TrioMath.decode(p6);
        ok(p6.length == 10 && k6 != null && k6.from == 5 && k6.reported.length == 6 && k6.reported[4] == -54, "6-device packet is 10 bytes (fits one legacy advertisement) and round-trips");
        ok(TrioMath.decode(new byte[]{'L', 'C', 1, 0, 0, 0, 0}) == null, "packets from the old beacon app are ignored");
        ok(TrioMath.decode(TrioMath.encode(0, new int[]{0, 0, 0}, 1)) != null && TrioMath.decode(new byte[]{'L', 'T', 5, 0, 0, 0, 0}) == null, "a sender id outside the mesh is rejected");

        // 3. window median ignores a wild reading and old samples
        TrioMath.Window w = new TrioMath.Window();
        for (int i = 0; i < 9; i++) w.add(-60, 1000 + i * 100);
        w.add(-95, 2000);
        ok(near(w.median(2100, 5000), -60, 1e-9), "median ignores a -95 dBm outlier");
        ok(Double.isNaN(w.median(20000, 5000)), "samples older than the window are dropped");

        // 4. layout: 3 devices (3-4-5 triangle), then 5 devices
        TrioMath.Layout t = TrioMath.layout(dists(new double[][]{{0, 0}, {3, 0}, {0, 4}}));
        ok(t != null && t.rms < 0.01 && near(Math.hypot(t.pts[1][0], t.pts[1][1]), 3, 0.01) && near(Math.hypot(t.pts[2][0] - t.pts[1][0], t.pts[2][1] - t.pts[1][1]), 5, 0.02), "3 devices: 3-4-5 triangle comes back exactly");
        double[][] truth5 = {{0, 0}, {6, 0}, {7, 5}, {2, 6}, {-2, 3}};
        TrioMath.Layout l5 = TrioMath.layout(dists(truth5));
        ok(l5 != null && shapeError(l5.pts, truth5) < 0.05 && l5.pts[1][1] == 0 || l5 != null && Math.abs(l5.pts[1][1]) < 1e-9, "5 devices: shape reproduced from exact distances, device 1 on the x axis");
        ok(l5 != null && l5.pts[2][1] >= 0, "device 2 is above the x axis (mirror fixed)");
        TrioMath.Layout impossible = TrioMath.layout(new double[][]{{0, 1, 1}, {1, 0, 5}, {1, 5, 0}});
        ok(impossible != null && impossible.rms > 0.5, "1, 1, 5 m cannot form a triangle: the layout reports a large disagreement");

        // 5. missing pairs
        double[][] d4 = dists(new double[][]{{0, 0}, {4, 0}, {4, 3}, {0, 3}});
        d4[0][2] = d4[2][0] = Double.NaN;
        ok(TrioMath.layout(d4) != null && shapeError(TrioMath.layout(d4).pts, new double[][]{{0, 0}, {4, 0}, {4, 3}, {0, 3}}) < 0.1, "4 devices with one diagonal missing (5 pairs) still lay out");
        d4[1][3] = d4[3][1] = Double.NaN;
        ok(TrioMath.layout(d4) == null, "4 devices with both diagonals missing (4 pairs) are not enough, no layout");

        // 6. a bad reading is spotted and down-weighted when there are spare readings
        double[][] dd = dists(truth5);
        dd[1][3] *= 1.8; dd[3][1] = dd[1][3];
        TrioMath.Layout lb = TrioMath.layout(dd);
        double worst = 0; int wi = -1, wj = -1;
        for (int i = 0; i < 5; i++) for (int j = i + 1; j < 5; j++) if (Math.abs(lb.resid[i][j]) > worst) { worst = Math.abs(lb.resid[i][j]); wi = i; wj = j; }
        ok(wi == 1 && wj == 3, "the corrupted B-D pair has the largest disagreement with the layout");
        ok(lb.weight[1][3] < 0.8, "and it is trusted less");
        double plain = shapeError(TrioMath.layout(dd).pts, truth5);
        ok(plain < 1.2, "layout stays close to the truth despite the bad pair (mean pair error " + String.format("%.2f", plain) + " m)");

        // 7. whole mesh with 5 devices exchanging packets
        int N = 5; double n = 2.2, r1 = -59;
        double[][] td = dists(truth5);
        TrioMesh[] m = new TrioMesh[N];
        for (int k = 0; k < N; k++) m[k] = new TrioMesh(k, N);
        long now = 10000;
        for (int step = 0; step < 30; step++) {
            now += 100;
            byte[][] ads = new byte[N][];
            for (int k = 0; k < N; k++) ads[k] = m[k].payload(now);
            for (int rx = 0; rx < N; rx++) for (int tx = 0; tx < N; tx++) {
                if (rx == tx) continue;
                int rssi = (int) Math.round(TrioMath.rssiAt(td[rx][tx], r1, n));
                TrioMath.Packet pk = TrioMath.decode(ads[tx]);
                m[rx].onPacket(tx, rssi, pk.reported, now);
            }
        }
        for (int k = 0; k < N; k++) { m[k].rssi1m = r1; m[k].pathLoss = n; }
        boolean allGood = true;
        for (int k = 0; k < N; k++) { TrioMath.Layout L = m[k].layout(now); allGood &= L != null && shapeError(L.pts, truth5) < 0.8; }
        ok(allGood, "every one of 5 devices draws the same layout (integer dBm rounding allowed)");
        ok(m[0].toJson(now, "t").contains("\"layout\":{") && m[0].toJson(now, "t").contains("\"n\":5"), "JSON carries the layout for the laptop page");

        // 8. one device that has heard only one peer gives no layout but still reports links
        TrioMesh solo = new TrioMesh(0, 3);
        solo.onPacket(1, -65, new int[]{0, 0, 0}, 5000);
        ok(solo.layout(5100) == null && solo.toJson(5100, "x").contains("\"layout\":null"), "no layout until enough links are known");
        ok(solo.heard(1, 5100) && !solo.heard(2, 5100), "heard() tracks which peers are in range");
        ok(!Double.isNaN(solo.linkRssi(0, 1, 5100)), "a pair with one direction missing still yields a reading");

        // 9. calibration
        TrioMesh c = new TrioMesh(0, 3);
        c.onPacket(1, (int) Math.round(TrioMath.rssiAt(2.0, -70, 2.2)), new int[]{0, 0, 0}, 1000);
        ok(c.calibrate(0, 1, 2.0, 1100) && near(c.rssi1m, -70, 0.6), "calibrate sets the 1 m reference from a known 2 m pair");

        System.out.println(checks - fails + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
