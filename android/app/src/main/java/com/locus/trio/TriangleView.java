package com.locus.trio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/** Draws the three phones as a triangle, with each side labelled in metres and coloured by how well the link is measured. */
public class TriangleView extends View {
    private TrioMath.Tri tri;
    private double[] dist = {Double.NaN, Double.NaN, Double.NaN};
    private int[] quality = {0, 0, 0}; // 0 none, 1 one direction, 2 both directions
    private boolean[] heard = new boolean[3];
    private int me;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TriangleView(Context c) { super(c); }

    public void set(TrioMath.Tri t, double[] d, int[] q, boolean[] h, int me) {
        tri = t; dist = d; quality = q; heard = h; this.me = me;
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) dp(240));
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#FFFFFF"));
        c.drawRoundRect(0, 0, W, H, dp(12), dp(12), p);
        if (tri == null) {
            p.setColor(Color.parseColor("#7A9AA6"));
            p.setTextSize(dp(14));
            p.setTextAlign(Paint.Align.CENTER);
            int n = 0;
            for (boolean b : heard) if (b) n++;
            c.drawText("Waiting for all three links…", W / 2, H / 2 - dp(6), p);
            c.drawText("other phones heard: " + n + " of 2", W / 2, H / 2 + dp(16), p);
            return;
        }
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] q : tri.pts) { minX = Math.min(minX, q[0]); maxX = Math.max(maxX, q[0]); minY = Math.min(minY, q[1]); maxY = Math.max(maxY, q[1]); }
        double spanX = Math.max(0.5, maxX - minX), spanY = Math.max(0.5, maxY - minY);
        float pad = dp(46);
        double s = Math.min((W - 2 * pad) / spanX, (H - 2 * pad) / spanY);
        float ox = (float) (W / 2 - s * (minX + maxX) / 2), oy = (float) (H / 2 + s * (minY + maxY) / 2);
        float[] X = new float[3], Y = new float[3];
        for (int k = 0; k < 3; k++) { X[k] = (float) (ox + s * tri.pts[k][0]); Y[k] = (float) (oy - s * tri.pts[k][1]); }
        Path fill = new Path();
        fill.moveTo(X[0], Y[0]); fill.lineTo(X[1], Y[1]); fill.lineTo(X[2], Y[2]); fill.close();
        p.setColor(Color.parseColor(tri.valid ? "#E6F7F1" : "#FDECEA"));
        c.drawPath(fill, p);
        p.setTextAlign(Paint.Align.CENTER);
        for (int pr = 0; pr < 3; pr++) {
            int a = TrioMath.pairA(pr), b = TrioMath.pairB(pr);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(quality[pr] == 2 ? 3 : 2));
            p.setColor(Color.parseColor(quality[pr] == 2 ? "#0B8A5E" : quality[pr] == 1 ? "#C98A1B" : "#9AB0B8"));
            c.drawLine(X[a], Y[a], X[b], Y[b], p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(13));
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setColor(Color.parseColor("#0B3A4A"));
            float mx = (X[a] + X[b]) / 2, my = (Y[a] + Y[b]) / 2;
            // push the label away from the triangle centre so it does not sit on the line
            float cx = (X[0] + X[1] + X[2]) / 3, cy = (Y[0] + Y[1] + Y[2]) / 3;
            float vx = mx - cx, vy = my - cy, len = Math.max(1f, (float) Math.hypot(vx, vy));
            c.drawText(String.format(java.util.Locale.US, "%.1f m", dist[pr]), mx + vx / len * dp(20), my + vy / len * dp(20) + dp(4), p);
        }
        for (int k = 0; k < 3; k++) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.parseColor(k == me ? "#0B3A4A" : "#22C3E6"));
            c.drawCircle(X[k], Y[k], dp(15), p);
            p.setColor(Color.WHITE);
            p.setTextSize(dp(15));
            c.drawText(String.valueOf((char) ('A' + k)), X[k], Y[k] + dp(5), p);
        }
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(dp(11));
        p.setColor(Color.parseColor("#7A9AA6"));
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(tri.valid ? "Shape only: the mirror image is equally possible." : "Distances disagree by " + String.format(java.util.Locale.US, "%.1f", tri.gap) + " m. Recalibrate or move the phones apart.", dp(10), H - dp(8), p);
    }
}
