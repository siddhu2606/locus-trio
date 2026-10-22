package com.locus.trio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/** Draws the devices where the layout puts them. Link colour: green = both directions measured, amber = one direction, red dashed = disagrees with the rest. */
public class NetworkView extends View {
    private TrioMath.Layout layout;
    private double[][] dist;
    private int[][] quality;
    private boolean[] heard = new boolean[0];
    private int me, n;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public NetworkView(Context c) { super(c); }

    public void set(TrioMath.Layout L, double[][] d, int[][] q, boolean[] h, int me) {
        layout = L; dist = d; quality = q; heard = h; this.me = me; n = h.length;
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(260)); }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawRoundRect(0, 0, W, H, dp(12), dp(12), p);
        if (layout == null) {
            p.setColor(Color.parseColor("#7A9AA6"));
            p.setTextSize(dp(14));
            p.setTextAlign(Paint.Align.CENTER);
            int k = 0;
            for (int i = 0; i < heard.length; i++) if (i != me && heard[i]) k++;
            c.drawText("Waiting for enough links…", W / 2, H / 2 - dp(6), p);
            c.drawText("other devices heard: " + k + " of " + (n - 1), W / 2, H / 2 + dp(16), p);
            return;
        }
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] q : layout.pts) { minX = Math.min(minX, q[0]); maxX = Math.max(maxX, q[0]); minY = Math.min(minY, q[1]); maxY = Math.max(maxY, q[1]); }
        double spanX = Math.max(0.5, maxX - minX), spanY = Math.max(0.5, maxY - minY);
        float pad = dp(48);
        double s = Math.min((W - 2 * pad) / spanX, (H - 2 * pad) / spanY);
        float ox = (float) (W / 2 - s * (minX + maxX) / 2), oy = (float) (H / 2 + s * (minY + maxY) / 2);
        float[] X = new float[n], Y = new float[n];
        float cx = 0, cy = 0;
        for (int k = 0; k < n; k++) { X[k] = (float) (ox + s * layout.pts[k][0]); Y[k] = (float) (oy - s * layout.pts[k][1]); cx += X[k] / n; cy += Y[k] / n; }
        p.setTextAlign(Paint.Align.CENTER);
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) {
            if (Double.isNaN(dist[a][b])) continue;
            boolean off = layout.weight[a][b] < 0.8;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(quality[a][b] == 2 ? 3 : 2));
            p.setColor(Color.parseColor(off ? "#C2412D" : quality[a][b] == 2 ? "#0B8A5E" : "#C98A1B"));
            p.setPathEffect(off ? new DashPathEffect(new float[]{dp(6), dp(5)}, 0) : null);
            c.drawLine(X[a], Y[a], X[b], Y[b], p);
            p.setPathEffect(null);
            if (n <= 4 || off) {
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(dp(12));
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setColor(Color.parseColor(off ? "#C2412D" : "#0B3A4A"));
                float mx = (X[a] + X[b]) / 2, my = (Y[a] + Y[b]) / 2, vx = mx - cx, vy = my - cy, len = Math.max(1f, (float) Math.hypot(vx, vy));
                c.drawText(String.format(java.util.Locale.US, "%.1f m", dist[a][b]), mx + vx / len * dp(14), my + vy / len * dp(14) + dp(4), p);
            }
        }
        for (int k = 0; k < n; k++) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.parseColor(k == me ? "#0B3A4A" : heard[k] ? "#22C3E6" : "#9AB0B8"));
            c.drawCircle(X[k], Y[k], dp(14), p);
            p.setColor(Color.WHITE);
            p.setTextSize(dp(14));
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(String.valueOf((char) ('A' + k)), X[k], Y[k] + dp(5), p);
        }
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(dp(11));
        p.setColor(Color.parseColor("#7A9AA6"));
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(String.format(java.util.Locale.US, "Shape only (mirror image possible). Typical disagreement %.1f m over %d links.", layout.rms, layout.links), dp(10), H - dp(8), p);
    }
}
