"""Checks the Python laptop node against the Java app: same packet bytes, same layout. Run: python tests/test_core.py"""
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "laptop"))
import trio_core as T  # noqa: E402

fails = checks = 0


def ok(c, what):
    global fails, checks
    checks += 1
    if not c:
        fails += 1
    print(("ok    " if c else "FAIL  ") + what)


# 1. packet bytes equal the Java encoder's bytes, and decode round-trips
ref = {}
for line in open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors.txt")):
    p = line.split()
    ref.setdefault(p[0], []).append(p[1:])
pk = T.encode(2, [-61, -70, 0], 300)
ok(pk.hex() == ref["packet"][0][0], "packet bytes are identical to the Java encoder (" + pk.hex() + ")")
d = T.decode(pk)
ok(d == (2, [-61, -70, 0], 300 & 0xFF), "decode round-trips")
ok(T.decode(bytes([ord("L"), ord("C"), 1, 0, 0, 0, 0])) is None, "old beacon-app packets are ignored")
ok(len(T.encode(5, [-50] * 6, 1)) == 10 and T.decode(T.encode(5, [-50] * 6, 1))[0] == 5, "6-device packet is 10 bytes")

# 2. layout matches the Java layout on the same distances (5 devices, one pair corrupted, one pair missing)
t = [(0, 0), (6, 0), (7, 5), (2, 6), (-2, 3)]
n = len(t)
D = [[math.hypot(t[i][0] - t[j][0], t[i][1] - t[j][1]) for j in range(n)] for i in range(n)]
D[1][3] *= 1.8
D[3][1] = D[1][3]
D[0][2] = D[2][0] = T.NAN
L = T.layout(D)
ok(abs(L.rms - float(ref["rms"][0][0])) < 1e-3, "layout disagreement matches Java (%.4f)" % L.rms)
worst = max(abs(L.pts[i][k] - float(ref["pt"][i][k + 1])) for i in range(n) for k in range(2))
ok(worst < 1e-3, "all 5 device positions match Java to within 1 mm (worst difference %.6f m)" % worst)
ok(abs(L.weight[1][3] - float(ref["w13"][0][0])) < 1e-3 and L.weight[1][3] < 0.8, "the corrupted pair is down-weighted the same way")

# 3. whole mesh with 4 devices exchanging packets
truth = [(0, 0), (5, 0), (5, 4), (0, 4)]
N = 4
mesh = [T.Mesh(k, N) for k in range(N)]
now = 100.0
for _ in range(30):
    now += 0.1
    ads = [m.payload(now) for m in mesh]
    for rx in range(N):
        for tx in range(N):
            if rx != tx:
                dd = math.hypot(truth[rx][0] - truth[tx][0], truth[rx][1] - truth[tx][1])
                frm, rep, _ = T.decode(ads[tx])
                mesh[rx].on_packet(frm, round(T.rssi_at(dd, -59, 2.2)), rep, now)
good = True
for m in mesh:
    Lm = m.layout if False else T.layout(m.distances(now))
    side = lambda a, b: math.hypot(Lm.pts[a][0] - Lm.pts[b][0], Lm.pts[a][1] - Lm.pts[b][1])
    good &= Lm is not None and abs(side(0, 1) - 5) < 0.8 and abs(side(1, 2) - 4) < 0.8
ok(good, "all 4 devices recover the 5 x 4 m rectangle")
js = mesh[0].to_json(now, "test")
ok(js["layout"] is not None and js["n"] == 4 and len(js["links"]) == 6, "JSON has the layout and all 6 links")

print("%d/%d checks passed" % (checks - fails, checks))
sys.exit(1 if fails else 0)
