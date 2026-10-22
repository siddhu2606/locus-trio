import com.locus.trio.TrioMath;
import java.util.Arrays;
import java.util.Random;

/**
 * Does adding devices help? Monte Carlo with the app's own layout code.
 * Noise model (dB, typical published values, NOT measured on our hardware):
 *   slow shadowing per link 3 (same both ways), per-device offset 1.5 (tx and rx), fast fading 3 per sample, median of 30 samples.
 * Devices are dropped at random in an 8 x 6 m room at least 1.5 m apart. Calibration: the real distance of device pair 0-1 is known, so that pair is left out of the error.
 * Result = mean error of all pair distances in the final layout, in metres, median and 90th percentile over the trials.
 * "Blocked" adds one random link with 8 dB extra loss (a wall or a body in the way).
 */
public class SimTrio {
    static double q(double[] a, double f) { double[] s = a.clone(); Arrays.sort(s); return s[Math.min(s.length - 1, (int) (f * s.length))]; }

    public static void main(String[] args) {
        double R1 = -59, NN = 2.2;
        System.out.println("devices  clean room (median / 90th pct)   one link blocked (median / 90th pct)   [mean pair-distance error, metres]");
        for (int n = 3; n <= 6; n++) {
            double[][] res = new double[2][];
            for (int mode = 0; mode < 2; mode++) {
                Random rnd = new Random(100 + n);
                double[] errs = new double[1500]; int c = 0;
                while (c < errs.length) {
                    double[][] pos = new double[n][2];
                    boolean okp = false;
                    while (!okp) {
                        okp = true;
                        for (int i = 0; i < n; i++) { pos[i][0] = rnd.nextDouble() * 8; pos[i][1] = rnd.nextDouble() * 6; }
                        for (int i = 0; i < n && okp; i++) for (int j = i + 1; j < n; j++) if (Math.hypot(pos[i][0] - pos[j][0], pos[i][1] - pos[j][1]) < 1.5) { okp = false; break; }
                    }
                    double[][] T = new double[n][n];
                    for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) T[i][j] = Math.hypot(pos[i][0] - pos[j][0], pos[i][1] - pos[j][1]);
                    double[] tx = new double[n], rx = new double[n];
                    for (int i = 0; i < n; i++) { tx[i] = rnd.nextGaussian() * 1.5; rx[i] = rnd.nextGaussian() * 1.5; }
                    int bi = -1, bj = -1;
                    if (mode == 1) { do { bi = rnd.nextInt(n); bj = rnd.nextInt(n); } while (bi == bj); }
                    double[][] S = new double[n][n];
                    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) { S[i][j] = S[j][i] = rnd.nextGaussian() * 3 + ((i == Math.min(bi, bj) && j == Math.max(bi, bj)) ? -8 : 0); }
                    double[][] avg = new double[n][n];
                    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                        double[] dir = new double[2];
                        for (int e = 0; e < 2; e++) {
                            int a = e == 0 ? i : j, b = e == 0 ? j : i;
                            double[] xs = new double[30];
                            for (int k = 0; k < 30; k++) xs[k] = TrioMath.rssiAt(T[i][j], R1, NN) + S[i][j] + tx[b] + rx[a] + rnd.nextGaussian() * 3;
                            Arrays.sort(xs); dir[e] = (xs[14] + xs[15]) / 2;
                        }
                        avg[i][j] = avg[j][i] = (dir[0] + dir[1]) / 2;
                    }
                    double r1 = TrioMath.calibrate1m(avg[0][1], T[0][1], NN);
                    double[][] D = new double[n][n];
                    for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) D[i][j] = i == j ? 0 : TrioMath.distance(avg[i][j], r1, NN);
                    TrioMath.Layout L = TrioMath.layout(D);
                    double s = 0; int cc = 0;
                    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) { if (i == 0 && j == 1) continue; s += Math.abs(Math.hypot(L.pts[i][0] - L.pts[j][0], L.pts[i][1] - L.pts[j][1]) - T[i][j]); cc++; }
                    errs[c++] = s / cc;
                }
                res[mode] = errs;
            }
            System.out.printf("   %d        %.2f / %.2f                          %.2f / %.2f%n", n, q(res[0], .5), q(res[0], .9), q(res[1], .5), q(res[1], .9));
        }
    }
}
