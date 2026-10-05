"""生成光柱用的散射贴图。

产物：assets/hatmod/textures/particle/beam.png
  - 32x64 的柔和光束条纹贴图：竖直方向是"流动"方向，横向是柔化的亮带。
  - 配合加色混合，让几何锥体看起来是发光的、有内部纹理的光柱。

用法： python tools/gen_beam_texture.py
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                    "assets", "hatmod", "textures", "particle")

WIDTH, HEIGHT = 32, 64


def write_png(path, width, height, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)

    def chunk(tag, data):
        payload = struct.pack(">I", len(data)) + tag + data
        return payload + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(png)


def build_beam():
    """横向柔化亮带 + 纵向条带，模拟光束内部的能量流。"""
    rows = [[(255, 255, 255, 0) for _ in range(WIDTH)] for _ in range(HEIGHT)]
    for y in range(HEIGHT):
        # 沿长度方向的明暗波动（流动感）
        phase = (y / HEIGHT) * 4.0
        wave = 0.65 + 0.35 * abs((phase % 1.0) - 0.5) * 2.0
        for x in range(WIDTH):
            # 横向：中间亮、两边柔化淡出
            nx = (x + 0.5) / WIDTH * 2.0 - 1.0
            edge = max(0.0, 1.0 - abs(nx))
            edge = edge ** 1.6
            a = edge * wave
            alpha = int(255 * a)
            if alpha <= 0:
                continue
            # 中心更白，边缘略带冷色，避免死白
            tint = int(255 - 40 * abs(nx))
            rows[y][x] = (tint, tint, 255, alpha)
    return rows


def preview(rows, step=4):
    for y in range(0, len(rows), step):
        line = ""
        for x in range(0, len(rows[0]), 1):
            r, g, b, a = rows[y][x]
            if a == 0:
                line += "."
            elif a < 90:
                line += ":"
            elif a < 190:
                line += "+"
            else:
                line += "#"
        print(line)


def main():
    rows = build_beam()
    write_png(os.path.join(ROOT, "beam.png"), WIDTH, HEIGHT, rows)
    preview(rows)
    print("beam texture written to", os.path.normpath(ROOT))


if __name__ == "__main__":
    main()
