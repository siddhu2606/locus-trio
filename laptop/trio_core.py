"""LOCUS Trio core for the laptop node: the same packet format, distance model and layout as the Android app.

Pure Python 3, no third-party packages. Mirrors android/app/src/main/java/com/locus/trio/TrioMath.java and TrioMesh.java.
"""
import math
import threading

MAGIC0, MAGIC1 = ord("L"), ord("T")
MIN_N, MAX_N = 3, 6
MAX_AGE = 5.0  # seconds; readings older than this are ignored
NAN = float("nan")


def isnan(x):
    return x != x


def distance(rssi_dbm, rssi1m, n):
    return 10 ** ((rssi1m - rssi_dbm) / (10 * n))


def rssi_at(d, rssi1m, n):
    return rssi1m - 10 * n * math.log10(max(d, 0.05))


def calibrate_1m(rssi_dbm, known_m, n):
    return rssi_dbm + 10 * n * math.log10(max(known_m, 0.05))


def combine(ij, ji):
    a, b = not isnan(ij), not isnan(ji)
    if a and b:
        return (ij + ji) / 2.0
    if a:
        return ij
    if b:
        return ji
    return NAN


def encode(device, reported, counter):
    """'L','T', device index + 1, a reading (signed dBm, 0 = unknown) per device, a counter. 4 + N bytes."""
    n = len(reported)
    out = bytearray([MAGIC0, MAGIC1, device + 1])
    for r in reported:
        out.append(max(-127, min(0, int(r))) & 0xFF)
    out.append(counter & 0xFF)
    assert len(out) == 4 + n
    return bytes(out)


def decode(data):
    """Returns (from_index, reported_list, counter) or None."""
    if data is None or not (4 + MIN_N <= len(data) <= 4 + MAX_N) or data[0] != MAGIC0 or data[1] != MAGIC1:
        return None
    n = len(data) - 4
    frm = data[2] - 1
    if frm < 0 or frm >= n:
        return None
    rep = [b - 256 if b > 127 else b for b in data[3:3 + n]]
    return frm, rep, data[3 + n]


class Window:
    """Sliding window of (time, rssi); the median resists the occasional wild reading."""

    def __init__(self):
        self.items = []

    def add(self, rssi, now):
        self.items.append((now, rssi))
        if len(self.items) > 96:
            del self.items[0]

    def median(self, now, max_age=MAX_AGE):
        v = sorted(r for t, r in self.items if now - t <= max_age)
        if not v:
            return NAN
        c = len(v)
        return v[c // 2] if c % 2 else (v[c // 2 - 1] + v[c // 2]) / 2.0


def _dist(x, i, j):
    return math.hypot(x[i][0] - x[j][0], x[i][1] - x[j][1])


def _stress(x, D, W):
    n, s = len(x), 0.0
    for i in range(n):
        for j in range(i + 1, n):
            if W[i][j] > 0:
                r = _dist(x, i, j) - D[i][j]
                s += W[i][j] * r * r
    return s


def _relax(x, D, W, iters):
    n = len(x)
    for _ in range(iters):
        for i in range(n):
            fx = fy = sw = 0.0
            for j in range(n):
                if i == j or W[i][j] <= 0:
                    continue
                dx, dy = x[j][0] - x[i][0], x[j][1] - x[i][1]
                d = max(1e-6, math.hypot(dx, dy))
                k = W[i][j] * (d - D[i][j]) / d
                fx += k * dx
                fy += k * dy
                sw += W[i][j]
            if sw > 0:
                x[i][0] += 0.5 * fx / sw
                x[i][1] += 0.5 * fy / sw


class Layout:
    pass


def layout(D):
    """Lay N devices out in 2D from a matrix of measured distances (NaN = not measured). Same algorithm as the Java version."""
    n = len(D)
    links = 0
    deg = [0] * n
    for i in range(n):
        for j in range(i + 1, n):
            if not isnan(D[i][j]):
                links += 1
                deg[i] += 1
                deg[j] += 1
    if n < MIN_N or links < 2 * n - 3 or any(d < 2 for d in deg):
        return None
    W = [[0.0] * n for _ in range(n)]
    mean = 0.0
    for i in range(n):
        for j in range(n):
            if i != j and not isnan(D[i][j]):
                W[i][j] = 1.0
                mean += D[i][j]
    mean /= 2.0 * links
    best = None
    for rnd in range(3):
        best, best_s = None, 1e18
        for k in range(8):
            x = []
            for i in range(n):
                ang = 2 * math.pi * i / n + 0.9 * k + 0.13 * k * i * i
                rad = mean * (0.6 + 0.12 * ((i + k) % 3))
                x.append([rad * math.cos(ang), rad * math.sin(ang)])
            _relax(x, D, W, 500)
            s = _stress(x, D, W)
            if s < best_s:
                best_s, best = s, x
        if rnd == 2:
            break
        rs = sorted(abs(_dist(best, i, j) - D[i][j]) for i in range(n) for j in range(i + 1, n) if not isnan(D[i][j]))
        k = max(0.35, 1.5 * rs[len(rs) // 2])
        for i in range(n):
            for j in range(n):
                if i != j and not isnan(D[i][j]):
                    r = abs(_dist(best, i, j) - D[i][j])
                    W[i][j] = 1.0 if r <= k else k / r
    x = best
    ox, oy = x[0][0], x[0][1]
    for p in x:
        p[0] -= ox
        p[1] -= oy
    a = math.atan2(x[1][1], x[1][0])
    ca, sa = math.cos(-a), math.sin(-a)
    for p in x:
        p[0], p[1] = p[0] * ca - p[1] * sa, p[0] * sa + p[1] * ca
    if x[2][1] < 0:
        for p in x:
            p[1] = -p[1]
    L = Layout()
    L.pts, L.links, L.weight = x, links, W
    L.resid = [[NAN] * n for _ in range(n)]
    ssum, c = 0.0, 0
    for i in range(n):
        for j in range(n):
            if i != j and not isnan(D[i][j]):
                L.resid[i][j] = _dist(x, i, j) - D[i][j]
                if i < j:
                    ssum += L.resid[i][j] ** 2
                    c += 1
    L.rms = math.sqrt(ssum / max(1, c))
    return L


class Mesh:
    """Everything one device knows about the mesh. Thread-safe: the scan thread writes, the web thread reads."""

    def __init__(self, me, n):
        self.me, self.n = me, n
        self.rssi1m, self.path_loss = -59.0, 2.2
        self.own = [Window() for _ in range(n)]
        self.remote = [[0] * n for _ in range(n)]
        self.remote_at = [[0.0] * n for _ in range(n)]
        self.heard_at = [0.0] * n
        self.packets = [0] * n
        self.counter = 0
        self.lock = threading.RLock()

    def on_packet(self, frm, rssi, reported, now):
        if frm == self.me or not (0 <= frm < self.n):
            return
        with self.lock:
            self.own[frm].add(rssi, now)
            self.heard_at[frm] = now
            self.packets[frm] += 1
            for k in range(min(self.n, len(reported))):
                self.remote[frm][k] = reported[k]
                self.remote_at[frm][k] = now

    def heard(self, p, now):
        return p == self.me or (self.heard_at[p] > 0 and now - self.heard_at[p] < MAX_AGE)

    def directional(self, frm, to, now):
        with self.lock:
            if frm == to:
                return NAN
            if frm == self.me:
                return self.own[to].median(now)
            if self.remote[frm][to] != 0 and now - self.remote_at[frm][to] <= MAX_AGE:
                return float(self.remote[frm][to])
            return NAN

    def link_rssi(self, i, j, now):
        return combine(self.directional(i, j, now), self.directional(j, i, now))

    def payload(self, now):
        with self.lock:
            rep = []
            for k in range(self.n):
                m = NAN if k == self.me else self.own[k].median(now)
                rep.append(0 if isnan(m) else int(round(m)))
            self.counter += 1
            return encode(self.me, rep, self.counter)

    def calibrate(self, i, j, known_m, now):
        r = self.link_rssi(i, j, now)
        if isnan(r):
            return False
        self.rssi1m = calibrate_1m(r, known_m, self.path_loss)
        return True

    def distances(self, now):
        d = [[0.0] * self.n for _ in range(self.n)]
        for i in range(self.n):
            for j in range(self.n):
                if i != j:
                    r = self.link_rssi(i, j, now)
                    d[i][j] = NAN if isnan(r) else distance(r, self.rssi1m, self.path_loss)
        return d

    def to_json(self, now, device):
        def num(x, dp):
            return None if x is None or isnan(x) else round(x, dp)

        with self.lock:
            d = self.distances(now)
            L = layout(d)
            links = []
            for a in range(self.n):
                for b in range(a + 1, self.n):
                    ab, ba = self.directional(a, b, now), self.directional(b, a, now)
                    links.append({
                        "a": a, "b": b, "ab": num(ab, 0), "ba": num(ba, 0), "rssi": num(combine(ab, ba), 1), "d": num(d[a][b], 2),
                        "agree": None if isnan(ab) or isnan(ba) else num(abs(ab - ba), 0),
                        "resid": None if L is None else num(L.resid[a][b], 2),
                        "trust": None if L is None or isnan(d[a][b]) else num(L.weight[a][b], 2)})
            out = {"device": device, "me": self.me, "n": self.n, "rssi1m": num(self.rssi1m, 1), "pathLoss": num(self.path_loss, 2),
                   "heard": [self.heard(k, now) for k in range(self.n)], "links": links, "layout": None}
            if L is not None:
                out["layout"] = {"rms": num(L.rms, 2), "links": L.links, "pts": [[num(p[0], 2), num(p[1], 2)] for p in L.pts]}
            return out
