"""LOCUS Trio laptop node: a Windows laptop joins the mesh as a full device (beacon and receiver at the same time).

    python trio_node.py --id D --n 4              join a 4-device mesh as device D
    python trio_node.py --scan-test               check that this laptop's Bluetooth can scan (6 seconds, then exit)

Receiving works on Windows, macOS and Linux (bleak). Advertising works on Windows 10/11 (winsdk). Elsewhere the node still
receives and draws the layout, but other devices will not hear it.
Open http://localhost:8080/ to see the layout. Other devices on the same Wi-Fi can open http://<this laptop's address>:8080/.
"""
import argparse
import asyncio
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import trio_core as T

COMPANY = 0xFFFF
HERE = os.path.dirname(os.path.abspath(__file__))
PAGE_CANDIDATES = [os.path.join(HERE, "trio.html"), os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "trio.html")]


class Advertiser:
    """Windows BLE advertisement publisher carrying the Trio payload. Updated about once a second."""

    def __init__(self):
        from winsdk.windows.devices.bluetooth.advertisement import BluetoothLEAdvertisementPublisher, BluetoothLEManufacturerData
        from winsdk.windows.storage.streams import DataWriter
        self._Pub, self._MD, self._DW = BluetoothLEAdvertisementPublisher, BluetoothLEManufacturerData, DataWriter
        self.pub = None

    def update(self, payload):
        if self.pub is not None:
            try:
                self.pub.stop()
            except Exception:
                pass
        pub = self._Pub()
        try:
            pub.use_extended_advertisement = False  # legacy advertisement so every phone and ESP32 can read it
        except Exception:
            pass
        md = self._MD()
        md.company_id = COMPANY
        w = self._DW()
        w.write_bytes(list(payload))
        md.data = w.detach_buffer()
        pub.advertisement.manufacturer_data.append(md)
        pub.start()
        self.pub = pub

    def stop(self):
        if self.pub is not None:
            try:
                self.pub.stop()
            except Exception:
                pass


def make_handler(mesh, device, page_path):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, body, ctype):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            u = urlparse(self.path)
            if u.path == "/api/state":
                self._send(200, json.dumps(mesh.to_json(time.time(), device)).encode(), "application/json")
            elif u.path == "/api/calibrate":
                # /api/calibrate?d=2.0  sets the 1 m reading so that devices A and B are d metres apart
                try:
                    d = float(parse_qs(u.query).get("d", ["1"])[0])
                except ValueError:
                    d = 1.0
                ok = mesh.calibrate(0, 1, d, time.time())
                self._send(200, json.dumps({"ok": ok, "rssi1m": mesh.rssi1m}).encode(), "application/json")
            elif u.path in ("/", "/index.html", "/trio.html"):
                self._send(200, open(page_path, "rb").read(), "text/html; charset=utf-8")
            else:
                self._send(404, b"not found", "text/plain")

    return H


async def run(args):
    from bleak import BleakScanner

    me, n = ord(args.id.upper()) - ord("A"), args.n
    if not (0 <= me < n):
        sys.exit("--id must be one of the first %d letters (A..%s)" % (n, chr(ord("A") + n - 1)))
    mesh = T.Mesh(me, n)
    mesh.rssi1m, mesh.path_loss = args.rssi1m, args.path_loss
    seen = {"any": 0, "trio": 0, "wrong_size": 0}

    def on_adv(device, adv):
        seen["any"] += 1
        data = adv.manufacturer_data.get(COMPANY)
        pkt = T.decode(bytes(data)) if data else None
        if pkt is None:
            return
        frm, rep, _ = pkt
        if len(rep) != n:
            seen["wrong_size"] = len(rep)
            return
        seen["trio"] += 1
        mesh.on_packet(frm, adv.rssi, rep, time.time())

    adv_out = None
    if not args.no_advertise:
        try:
            adv_out = Advertiser()
        except Exception as e:
            print("Advertising is not available here (%s). Receiving only; other devices will not hear this one." % e)

    page = next((p for p in PAGE_CANDIDATES if os.path.exists(p)), None)
    if page is None:
        sys.exit("trio.html not found next to this script or in android/app/src/main/assets/")
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(mesh, "laptop " + os.environ.get("COMPUTERNAME", "node"), page))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("Device %s of %d. Layout page: http://localhost:%d/" % (args.id.upper(), n, args.port))

    scanner = BleakScanner(on_adv)
    await scanner.start()
    last_print = 0.0
    try:
        while True:
            now = time.time()
            if adv_out is not None:
                adv_out.update(mesh.payload(now))
            if now - last_print > 2.0:
                last_print = now
                heard = "".join(chr(ord("A") + k) for k in range(n) if k != me and mesh.heard(k, now)) or "none"
                L = T.layout(mesh.distances(now))
                msg = "heard: %-8s  bluetooth packets: %d  trio packets: %d" % (heard, seen["any"], seen["trio"])
                if L is not None:
                    msg += "  layout from %d links, disagreement %.1f m" % (L.links, L.rms)
                if seen["wrong_size"]:
                    msg += "  (another device uses a mesh of %d)" % seen["wrong_size"]
                print(msg)
            await asyncio.sleep(0.9)
    finally:
        await scanner.stop()
        if adv_out:
            adv_out.stop()


async def scan_test():
    from bleak import BleakScanner

    found, trio = {}, set()

    def cb(device, adv):
        found[device.address] = adv.rssi
        d = adv.manufacturer_data.get(COMPANY)
        if d and T.decode(bytes(d)):
            trio.add(T.decode(bytes(d))[0])

    async with BleakScanner(cb):
        await asyncio.sleep(6)
    print("Bluetooth scan works: %d devices heard in 6 s." % len(found))
    print("Trio devices heard: %s" % (", ".join(chr(ord("A") + k) for k in sorted(trio)) or "none"))
    try:
        Advertiser()
        print("Advertising is available (winsdk found).")
    except Exception as e:
        print("Advertising is not available: %s" % e)


def main():
    ap = argparse.ArgumentParser(description="LOCUS Trio laptop node")
    ap.add_argument("--id", default="D", help="this device's letter, A..F (default D)")
    ap.add_argument("--n", type=int, default=4, help="devices in the mesh, 3..6 (must match every other device)")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--rssi1m", type=float, default=-59.0, help="signal strength at 1 m in dBm")
    ap.add_argument("--path-loss", type=float, default=2.2, dest="path_loss", help="path-loss exponent, higher in cluttered rooms")
    ap.add_argument("--no-advertise", action="store_true", help="receive only")
    ap.add_argument("--scan-test", action="store_true", help="check Bluetooth for 6 seconds and exit")
    args = ap.parse_args()
    try:
        asyncio.run(scan_test() if args.scan_test else run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
