import com.locus.trio.TrioMath;

/** Prints reference vectors from the Java implementation. The Python node is checked against tests/vectors.txt. */
public class DumpVectors {
    public static void main(String[] a) {
        double[][] t = {{0, 0}, {6, 0}, {7, 5}, {2, 6}, {-2, 3}};
        int n = t.length; double[][] D = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) D[i][j] = Math.hypot(t[i][0] - t[j][0], t[i][1] - t[j][1]);
        D[1][3] *= 1.8; D[3][1] = D[1][3];
        D[0][2] = D[2][0] = Double.NaN;
        TrioMath.Layout L = TrioMath.layout(D);
        StringBuilder sb = new StringBuilder("packet ");
        for (byte b : TrioMath.encode(2, new int[]{-61, -70, 0}, 300)) sb.append(String.format("%02x", b));
        System.out.println(sb);
        System.out.println("rms " + String.format("%.6f", L.rms));
        for (int i = 0; i < n; i++) System.out.println("pt " + i + " " + String.format("%.6f %.6f", L.pts[i][0], L.pts[i][1]));
        System.out.println("w13 " + String.format("%.6f", L.weight[1][3]));
    }
}
