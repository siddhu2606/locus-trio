import com.locus.trio.TrioMath;
import com.locus.trio.TrioMesh;

/** Laptop test of the Trio maths. Run: tests/run_tests.sh (needs only a JDK). Exits non-zero on any failure. */
public class TrioTest {
    static int fails = 0, checks = 0;

    static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("FAIL  " + what); } else System.out.println("ok    " + what); }

    static boolean near(double a, double b, double tol) { return Math.abs(a - b) <= tol; }

    static double side(double[][] p, int i, int j) { return Math.hypot(p[i][0] - p[j][0], p[i][1] - p[j][1]); }

    public static void main(String[] args) {
        // 1. path-loss round trip and calibration
        double r = TrioMath.rssiAt(3.0, -59, 2.2);
        ok(near(TrioMath.distance(r, -59, 2.2), 3.0, 1e-9), "distance(rssiAt(3 m)) == 3 m");
        ok(near(TrioMath.calibrate1m(r, 3.0, 2.2), -59, 1e-9), "calibrate1m recovers the 1 m reference");

        // 2. a 3-4-5 triangle comes back with the same side lengths
        TrioMath.Tri t = TrioMath.triangle(3, 4, 5);
        ok(t.valid && near(side(t.pts, 0, 1), 3, 1e-9) && near(side(t.pts, 0, 2), 4, 1e-9) && near(side(t.pts, 1, 2), 5, 1e-9), "3-4-5 layout keeps all three sides");
        ok(t.pts[2][1] >= 0, "C is placed above the A-B line");

        // 3. impossible distances are flagged, not silently drawn
        TrioMath.Tri bad = TrioMath.triangle(1, 1, 5);
        ok(!bad.valid && near(bad.gap, 3, 1e-9), "1,1,5 breaks the triangle inequality by 3 m");

        // 4. payload round trip
        byte[] p = TrioMath.encode(2, new int[]{-61, -70, 0}, 300);
        int[] dec = TrioMath.decode(p);
        ok(dec != null && dec[0] == 2 && dec[1] == -61 && dec[2] == -70 && dec[3] == 0 && dec[4] == (300 & 0xFF), "encode/decode keeps phone id, three readings and counter");
        ok(TrioMath.decode(new byte[]{'L', 'C', 1, 0, 0, 0, 0}) == null, "packets from the old beacon app are ignored");
        ok(p.length == 7, "payload is 7 bytes (fits in one legacy advertisement)");

        // 5. window median ignores a wild reading and old samples
        TrioMath.Window w = new TrioMath.Window();
        for (int i = 0; i < 9; i++) w.add(-60, 1000 + i * 100);
        w.add(-95, 2000);
        ok(near(w.median(2100, 5000), -60, 1e-9), "median ignores a -95 dBm outlier");
        ok(Double.isNaN(w.median(20000, 5000)), "samples older than the window are dropped");

        // 6. whole mesh: simulate three phones exchanging payloads, true sides 3 / 4 / 5 m
        double n = 2.2, r1 = -59;
        double[] truth = {3, 4, 5}; // AB, AC, BC
        TrioMesh[] m = {new TrioMesh(0), new TrioMesh(1), new TrioMesh(2)};
        long now = 10000;
        for (int step = 0; step < 30; step++) {
            now += 100;
            byte[][] ads = new byte[3][];
            for (int k = 0; k < 3; k++) ads[k] = m[k].payload(now);
            for (int rx = 0; rx < 3; rx++) for (int tx = 0; tx < 3; tx++) {
                if (rx == tx) continue;
                double d = truth[TrioMath.pair(rx, tx)];
                int rssi = (int) Math.round(TrioMath.rssiAt(d, r1, n));
                int[] rep = TrioMath.decode(ads[tx]);
                m[rx].onPacket(tx, rssi, new int[]{rep[1], rep[2], rep[3]}, now);
            }
        }
        for (int k = 0; k < 3; k++) { m[k].rssi1m = r1; m[k].n = n; }
        for (int k = 0; k < 3; k++) {
            double[] d = m[k].distances(now);
            ok(near(d[0], 3, 0.6) && near(d[1], 4, 0.8) && near(d[2], 5, 1.0), "phone " + "ABC".charAt(k) + " recovers 3/4/5 m (integer dBm rounding allowed)");
        }
        ok(near(m[0].distances(now)[2], m[2].distances(now)[2], 1e-9), "all phones agree on the same B-C distance (they share readings)");
        ok(m[0].toJson(now, "test").contains("\"valid\":true"), "JSON reports a valid triangle");

        // 7. a phone that has heard only one peer gives no triangle but still reports links
        TrioMesh solo = new TrioMesh(0);
        solo.onPacket(1, -65, new int[]{0, 0, 0}, 5000);
        ok(solo.triangle(5100) == null && solo.toJson(5100, "x").contains("\"tri\":null"), "no triangle until all three links are known");
        ok(solo.heard(1, 5100) && !solo.heard(2, 5100), "heard() tracks which peers are in range");

        // 8. a one-way link is still used (the other direction is missing)
        ok(!Double.isNaN(solo.linkRssi(0, 1, 5100)), "a pair with one direction missing still yields a reading");

        // 9. calibration
        TrioMesh c = new TrioMesh(0);
        c.onPacket(1, (int) Math.round(TrioMath.rssiAt(2.0, -70, 2.2)), new int[]{0, 0, 0}, 1000);
        ok(c.calibrate(0, 1, 2.0, 1100) && near(c.rssi1m, -70, 0.6), "calibrate sets the 1 m reference from a known 2 m pair");

        System.out.println(checks - fails + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
