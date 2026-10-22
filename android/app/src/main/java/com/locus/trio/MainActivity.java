package com.locus.trio;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * LOCUS Trio: 3 to 6 devices, each one both beacon and receiver, at the same time.
 * Every device advertises a small packet and scans for the others. The packet also carries the readings that device has made,
 * so all devices end up holding every directional reading and draw the same layout. No fixed anchors, no Wi-Fi needed.
 */
public class MainActivity extends Activity {
    static final int COMPANY = 0xFFFF;
    static final int PORT = 8080;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView status, advLine, scanLine, table, addr, calInfo;
    private NetworkView triView;
    private SharedPreferences prefs;

    private int me = 0, n = 3;
    private TrioMesh mesh;
    private boolean running = false, advertising = false, scanning = false, useLegacy = false, advStarting = false;
    private BluetoothLeAdvertiser advertiser;
    private AdvertisingSet advSet;
    private BluetoothLeScanner scanner;
    private TrioServer server;
    private long scanStarted, tickNo = 0;
    private int anySeen = 0, trioSeen = 0, sizeMismatch = 0;
    private double knownMetres = 1.0;
    private String advNote = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("trio", MODE_PRIVATE);
        n = Math.max(TrioMath.MIN_N, Math.min(TrioMath.MAX_N, prefs.getInt("n", 3)));
        me = Math.min(prefs.getInt("me", 0), n - 1);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(28), p, p);
        sv.addView(root);
        sv.setBackgroundColor(Color.parseColor("#F4FCFE"));
        setContentView(sv);
        home();
        ui.post(tick);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, boolean bold, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, String bg, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setTextColor(bg.equals("#FFFFFF") ? Color.parseColor("#0B3A4A") : Color.WHITE);
        x.setBackgroundColor(Color.parseColor(bg));
        x.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(6), 0, dp(6));
        x.setLayoutParams(lp);
        return x;
    }

    private Button small(String s, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        x.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        return x;
    }

    // ---------- home ----------
    private void home() {
        stopAll();
        root.removeAllViews();
        root.addView(text("LOCUS Trio", 26, true, "#0B3A4A"));
        root.addView(text("Every device is a beacon and a receiver at the same time. Together they work out the shape of the layout between them. Works with 3 to 6 devices: phones, a laptop running the Python node, ESP32 boards.", 15, false, "#3F6572"));
        root.addView(text("How many devices are in the mesh?", 16, true, "#0B3A4A"));
        LinearLayout cnt = new LinearLayout(this);
        cnt.setOrientation(LinearLayout.HORIZONTAL);
        for (int k = TrioMath.MIN_N; k <= TrioMath.MAX_N; k++) {
            final int kk = k;
            Button x = small(String.valueOf(k), v -> { n = kk; me = Math.min(me, n - 1); prefs.edit().putInt("n", n).putInt("me", me).apply(); home(); });
            x.setAlpha(k == n ? 1f : 0.45f);
            cnt.addView(x);
        }
        root.addView(cnt);
        root.addView(text("Which device is this one? Give every device a different letter.", 16, true, "#0B3A4A"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] bs = new Button[n];
        for (int i = 0; i < n; i++) {
            final int id = i;
            Button x = small(String.valueOf((char) ('A' + i)), v -> { me = id; prefs.edit().putInt("me", me).apply(); for (int k = 0; k < n; k++) bs[k].setAlpha(k == id ? 1f : 0.45f); });
            x.setAlpha(i == me ? 1f : 0.45f);
            bs[i] = x;
            row.addView(x);
        }
        root.addView(row);
        root.addView(button("Start", "#10B981", v -> startRun()));
        root.addView(text("Before you press Start: Bluetooth on, Location on. Keep the screen on and the app open. Place the devices a few metres apart, at the same height. A laptop or ESP32 joins by using one of the letters.", 13, false, "#3F6572"));
    }

    // ---------- permissions ----------
    private String[] needed() {
        List<String> l = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            l.add(Manifest.permission.BLUETOOTH_SCAN);
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        l.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return l.toArray(new String[0]);
    }

    private boolean granted() {
        for (String p : needed()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean ok = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (!ok && status != null) status.setText("Permission denied. Allow Nearby devices and Location for this app in Settings.");
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    // ---------- running screen ----------
    private void startRun() {
        mesh = new TrioMesh(me, n);
        mesh.rssi1m = prefs.getFloat("rssi1m", -59f);
        mesh.pathLoss = prefs.getFloat("pl", 2.2f);
        knownMetres = prefs.getFloat("known", 1.0f);
        running = true;
        anySeen = 0; trioSeen = 0; advNote = "";
        root.removeAllViews();
        root.addView(text("Device " + (char) ('A' + me) + " of " + n + " is running", 22, true, "#0B3A4A"));
        status = text("Starting…", 15, true, "#0B8A5E");
        advLine = text("", 13, false, "#3F6572");
        scanLine = text("", 13, false, "#3F6572");
        root.addView(status);
        root.addView(advLine);
        root.addView(scanLine);
        triView = new NetworkView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        lp.setMargins(0, dp(8), 0, dp(8));
        triView.setLayoutParams(lp);
        root.addView(triView);
        table = text("", 13, false, "#0B3A4A");
        table.setTypeface(Typeface.MONOSPACE);
        root.addView(table);

        root.addView(text("Distance calibration", 16, true, "#0B3A4A"));
        calInfo = text("", 13, false, "#3F6572");
        root.addView(calInfo);
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(small("Real A–B −", v -> { knownMetres = Math.max(0.5, knownMetres - 0.5); prefs.edit().putFloat("known", (float) knownMetres).apply(); }));
        r1.addView(small("Real A–B +", v -> { knownMetres += 0.5; prefs.edit().putFloat("known", (float) knownMetres).apply(); }));
        root.addView(r1);
        root.addView(button("Calibrate with the real A–B distance", "#22C3E6", v -> {
            if (mesh.calibrate(0, 1, knownMetres, System.currentTimeMillis())) { prefs.edit().putFloat("rssi1m", (float) mesh.rssi1m).apply(); status.setText("Calibrated: A–B is now " + String.format(Locale.US, "%.1f", knownMetres) + " m."); }
            else status.setText("The A–B link has not been heard yet. Wait a few seconds with both devices running.");
        }));
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(small("Walls / clutter −", v -> { mesh.pathLoss = Math.max(1.6, mesh.pathLoss - 0.1); prefs.edit().putFloat("pl", (float) mesh.pathLoss).apply(); }));
        r2.addView(small("Walls / clutter +", v -> { mesh.pathLoss = Math.min(4.0, mesh.pathLoss + 0.1); prefs.edit().putFloat("pl", (float) mesh.pathLoss).apply(); }));
        root.addView(r2);
        root.addView(text("Laptop view (same Wi-Fi or this phone's hotspot):", 14, true, "#0B3A4A"));
        addr = text("", 13, false, "#0B3A4A");
        root.addView(addr);
        root.addView(button("Open the page on this phone", "#22C3E6", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + PORT + "/")))));
        root.addView(button("Stop and go back", "#FFFFFF", v -> home()));

        try {
            if (server == null) { server = new TrioServer(getAssets(), PORT, () -> mesh == null ? "{}" : mesh.toJson(System.currentTimeMillis(), Build.MODEL)); server.start(); }
        } catch (Exception e) { server = null; advNote = "Laptop page unavailable: " + e.getMessage(); }
        if (!granted()) requestPermissions(needed(), 7);
        tryStart();
    }

    /** Starts scanning and advertising as soon as everything is ready. Called every tick, so turning Location on later works without reopening. */
    private void tryStart() {
        if (!running) return;
        if (!granted()) { if (status != null) status.setText("Waiting for permission (Nearby devices + Location)…"); return; }
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { status.setText("Turn Bluetooth on."); return; }
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isLocationEnabled()) { status.setText("Turn Location ON. Android needs it for Bluetooth scanning. It starts by itself once it is on."); return; }
        }
        if (!scanning) startScan(a);
        if (!advertising && !advStarting) startAdvertising(a);
    }

    private void startScan(BluetoothAdapter a) {
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) { status.setText("Bluetooth scanner unavailable."); return; }
        // No hardware filter: some phones drop manufacturer-data filters. Packets are filtered in handle().
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build();
        try { scanner.startScan(null, s, scanCb); } catch (Exception e) { status.setText("Scan could not start: " + e.getMessage()); return; }
        scanning = true;
        scanStarted = System.currentTimeMillis();
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { handle(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) handle(r); }
        @Override public void onScanFailed(int e) { scanning = false; if (status != null) status.setText("Scan failed (code " + e + "). Turn Bluetooth off and on."); }
    };

    private void handle(ScanResult r) {
        anySeen++;
        if (r.getScanRecord() == null || mesh == null) return;
        SparseArray<byte[]> m = r.getScanRecord().getManufacturerSpecificData();
        byte[] d = m == null ? null : m.get(COMPANY);
        TrioMath.Packet pkt = TrioMath.decode(d);
        if (pkt == null) return;
        if (pkt.reported.length != n) { sizeMismatch = pkt.reported.length; return; }
        trioSeen++;
        mesh.onPacket(pkt.from, r.getRssi(), pkt.reported, System.currentTimeMillis());
    }

    // ---------- advertising (updatable set, with a restart fallback for phones that refuse it) ----------
    private AdvertiseData adData() {
        return new AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                .addManufacturerData(COMPANY, mesh.payload(System.currentTimeMillis())).build();
    }

    private final AdvertisingSetCallback setCb = new AdvertisingSetCallback() {
        @Override public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int st) {
            advStarting = false;
            if (st == ADVERTISE_SUCCESS) { advSet = set; advertising = true; useLegacy = false; advNote = "Advertising (live-updating set, tx power " + txPower + " dBm)."; }
            else { advertising = false; useLegacy = true; advNote = "Updatable advertising refused (code " + st + "), using restart mode."; }
        }
        @Override public void onAdvertisingSetStopped(AdvertisingSet set) { advSet = null; }
    };

    private final AdvertiseCallback legacyCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) { advStarting = false; advertising = true; advNote = "Advertising (restart mode)."; }
        @Override public void onStartFailure(int e) {
            advStarting = false; advertising = false;
            advNote = "Advertising failed (code " + e + ")" + (e == 5 ? ": this phone cannot advertise over BLE, so it cannot join the mesh." : e == 2 ? ": too many advertisers, close other Bluetooth apps." : "");
        }
    };

    private void startAdvertising(BluetoothAdapter a) {
        advertiser = a.getBluetoothLeAdvertiser();
        if (advertiser == null) { advNote = "This phone has no BLE advertising (no peripheral mode), so it cannot join the mesh."; return; }
        advStarting = true;
        if (!useLegacy) {
            try {
                AdvertisingSetParameters ps = new AdvertisingSetParameters.Builder().setLegacyMode(true).setConnectable(false).setScannable(false)
                        .setInterval(AdvertisingSetParameters.INTERVAL_LOW).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH).build();
                advertiser.startAdvertisingSet(ps, adData(), null, null, null, setCb);
                return;
            } catch (Exception e) { useLegacy = true; advNote = "Updatable advertising unavailable (" + e.getMessage() + "), using restart mode."; }
        }
        startLegacy();
    }

    private void startLegacy() {
        try {
            AdvertiseSettings s = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).setTimeout(0).build();
            advertiser.startAdvertising(s, adData(), legacyCb);
        } catch (Exception e) { advStarting = false; advNote = "Advertising failed: " + e.getMessage(); }
    }

    /** Called every tick: put this phone's latest readings into its advertisement. */
    private void pushPayload() {
        if (!advertising || mesh == null) return;
        try {
            if (advSet != null && !useLegacy) advSet.setAdvertisingData(adData());
            else if (useLegacy && advertiser != null && tickNo % 2 == 0) { advertiser.stopAdvertising(legacyCb); advStarting = true; startLegacy(); }
        } catch (Exception ignored) { }
    }

    private void stopAll() {
        running = false;
        try { if (advertiser != null) { if (advSet != null) advertiser.stopAdvertisingSet(setCb); advertiser.stopAdvertising(legacyCb); } } catch (Exception ignored) { }
        try { if (scanner != null && scanning) scanner.stopScan(scanCb); } catch (Exception ignored) { }
        advertising = false; scanning = false; advStarting = false; advSet = null;
        if (server != null) { server.stop(); server = null; }
    }

    // ---------- refresh ----------
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickNo++;
            if (running && mesh != null && table != null) {
                tryStart();
                pushPayload();
                long now = System.currentTimeMillis();
                boolean[] heard = new boolean[n];
                int nHeard = 0;
                for (int k = 0; k < n; k++) { heard[k] = mesh.heard(k, now); if (k != me && heard[k]) nHeard++; }
                double[][] d = mesh.distances(now);
                int[][] q = new int[n][n];
                StringBuilder sb = new StringBuilder();
                sb.append("link   hears / heard-by    dist\n");
                for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) {
                    double ab = mesh.directional(a, b, now), ba = mesh.directional(b, a, now);
                    q[a][b] = q[b][a] = (Double.isNaN(ab) ? 0 : 1) + (Double.isNaN(ba) ? 0 : 1);
                    sb.append(String.format(Locale.US, "%c-%c %6s / %-6s %7s\n", (char) ('A' + a), (char) ('A' + b), Double.isNaN(ab) ? "-" : String.format(Locale.US, "%.0f", ab),
                            Double.isNaN(ba) ? "-" : String.format(Locale.US, "%.0f", ba), Double.isNaN(d[a][b]) ? "-" : String.format(Locale.US, "%.1f m", d[a][b])));
                }
                table.setText(sb.toString());
                triView.set(mesh.layout(now), d, q, heard, me);
                if (advertising && scanning && !status.getText().toString().startsWith("Calibrated"))
                    status.setText(nHeard == n - 1 ? "All other devices in range" : nHeard + " of " + (n - 1) + " other devices in range");
                advLine.setText("Beacon role: " + (advertising ? "on" : "off") + ". " + advNote);
                String scanMsg = "Receiver role: " + (scanning ? "on" : "off") + ". Bluetooth packets heard: " + anySeen + ", Trio packets: " + trioSeen + ".";
                if (sizeMismatch != 0) scanMsg += "\nAnother device uses a mesh of " + sizeMismatch + " devices; this one is set to " + n + ". Set every device to the same number.";
                if (scanning && anySeen == 0 && now - scanStarted > 6000) scanMsg += "\nHears nothing: check Location is ON and Nearby devices + Location are allowed.";
                else if (scanning && anySeen > 0 && trioSeen == 0 && sizeMismatch == 0 && now - scanStarted > 8000) scanMsg += "\nScanning works but no other Trio device yet. Is it running and close?";
                scanLine.setText(scanMsg);
                calInfo.setText(String.format(Locale.US, "Real A–B distance to use: %.1f m   |   1 m reading: %.0f dBm   |   path-loss exponent: %.1f", knownMetres, mesh.rssi1m, mesh.pathLoss));
                StringBuilder u = new StringBuilder();
                for (String ip : ips()) u.append("http://").append(ip).append(':').append(PORT).append("/\n");
                if (u.length() == 0) u.append("(no network: turn on this phone's hotspot or join Wi-Fi)\n");
                addr.setText(u.toString());
            }
            ui.postDelayed(this, 700);
        }
    };

    private List<String> ips() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        return out;
    }

    @Override
    protected void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
