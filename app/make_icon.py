#!/usr/bin/env python3
"""生成 App 图标：深色圆角底 + 青色脉冲波形。纯 zlib，不依赖 PIL。"""
import zlib, struct, os, math

def png(path, w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw += bytes(px[y][x])
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    open(path, "wb").write(out)

def blend(dst, src, a):
    return tuple(int(round(dst[i] * (1 - a) + src[i] * a)) for i in range(3))

def render(S):
    # 背景圆角方
    R = int(S * 0.235)
    top  = (14, 20, 38)      # #0e1426
    bot  = (8, 10, 20)       # #080a14
    px = [[(0, 0, 0, 0) for _ in range(S)] for _ in range(S)]

    def inside_rrect(x, y):
        cx = min(max(x, R), S - 1 - R)
        cy = min(max(y, R), S - 1 - R)
        dx, dy = x - cx, y - cy
        return dx * dx + dy * dy <= R * R

    for y in range(S):
        t = y / (S - 1)
        base = tuple(int(round(top[i] * (1 - t) + bot[i] * t)) for i in range(3))
        for x in range(S):
            if inside_rrect(x, y):
                px[y][x] = (base[0], base[1], base[2], 255)

    # 左上角的青色光晕
    gx, gy, gr = S * 0.20, S * 0.16, S * 0.72
    for y in range(S):
        for x in range(S):
            if px[y][x][3] == 0: continue
            d = math.hypot(x - gx, y - gy) / gr
            if d < 1:
                a = (1 - d) ** 2 * 0.42
                px[y][x] = blend(px[y][x][:3], (10, 132, 255), a) + (255,)

    # 脉冲波形（粗线 + 圆头）
    CY = S * 0.52
    AMP = S * 0.20
    pts = [(0.06, 0), (0.24, 0), (0.33, -0.55), (0.44, 1.0), (0.55, -0.30),
           (0.65, 0.10), (0.94, 0)]
    poly = [(S * u, CY + AMP * v) for u, v in pts]
    W = max(2.0, S * 0.062)
    for y in range(S):
        for x in range(S):
            if px[y][x][3] == 0: continue
            best = 1e9
            for k in range(len(poly) - 1):
                x1, y1 = poly[k]; x2, y2 = poly[k + 1]
                vx, vy = x2 - x1, y2 - y1
                L2 = vx * vx + vy * vy
                tt = 0 if L2 == 0 else max(0, min(1, ((x - x1) * vx + (y - y1) * vy) / L2))
                d = math.hypot(x - (x1 + tt * vx), y - (y1 + tt * vy))
                if d < best: best = d
            if best < W:
                a = 1.0 if best < W - 1.5 else (W - best) / 1.5
                px[y][x] = blend(px[y][x][:3], (63, 209, 255), a * 0.97) + (255,)

    # 波形右端一个小亮点
    dx0, dy0, r0 = poly[-1][0], poly[-1][1], S * 0.085
    for y in range(S):
        for x in range(S):
            if px[y][x][3] == 0: continue
            d = math.hypot(x - dx0, y - dy0)
            if d < r0:
                a = (1 - d / r0) ** 1.6
                px[y][x] = blend(px[y][x][:3], (190, 245, 255), a) + (255,)
    return px

for d, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    os.makedirs("res/" + d, exist_ok=True)
    png("res/%s/ic_launcher.png" % d, size, size, render(size))
    print("  res/%s/ic_launcher.png  %dpx" % (d, size))
print("图标生成完毕")
