package com.locus.trio;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Tiny HTTP server so a laptop on the same Wi-Fi / hotspot can watch the trio.
 *   GET /           the Trio page (bundled in the APK)
 *   GET /api/state  this phone's view of the three phones as JSON
 */
public class TrioServer {
    private final AssetManager assets;
    private final int port;
    private final Supplier<String> state;
    private ServerSocket server;
    private volatile boolean running;
    private byte[] pageCache;

    public TrioServer(AssetManager assets, int port, Supplier<String> state) {
        this.assets = assets;
        this.port = port;
        this.state = state;
    }

    public void start() throws Exception {
        server = new ServerSocket(port, 20, (InetAddress) null);
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket c = server.accept();
                    new Thread(() -> handle(c)).start();
                } catch (Exception e) {
                    if (!running) return;
                }
            }
        }, "trio-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private byte[] page() throws Exception {
        if (pageCache == null) {
            try (InputStream in = assets.open("trio.html")) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                pageCache = o.toByteArray();
            }
        }
        return pageCache;
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(3000);
            InputStream in = c.getInputStream();
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1 && ch != '\n') if (ch != '\r') line.append((char) ch);
            int run = 0;
            while (run < 2 && (ch = in.read()) != -1) { if (ch == '\n') run++; else if (ch != '\r') run = 0; }
            String[] p = line.toString().split(" ");
            String method = p.length > 0 ? p[0] : "", target = p.length > 1 ? p[1] : "/";
            int q = target.indexOf('?');
            String path = q >= 0 ? target.substring(0, q) : target;
            byte[] body;
            String type = "application/json";
            int code = 200;
            if (method.equals("OPTIONS")) { body = new byte[0]; code = 204; }
            else if (path.equals("/api/state")) body = state.get().getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/") || path.equals("/index.html") || path.equals("/trio.html")) { body = page(); type = "text/html; charset=utf-8"; }
            else { body = "not found".getBytes(StandardCharsets.UTF_8); type = "text/plain"; code = 404; }
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 204 ? " No Content" : " Not Found") + "\r\nContent-Type: " + type
                    + "\r\nContent-Length: " + body.length + "\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
            OutputStream out = c.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { c.close(); } catch (Exception ignored) { }
        }
    }
}
