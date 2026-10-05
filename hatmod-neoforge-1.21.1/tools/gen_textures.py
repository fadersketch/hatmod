"""生成神之牛仔帽（Hat Mod）所需的全部贴图（纯标准库，无需 Pillow）。

产物：
  hatmod_logo.png                                                       256x256 模组图标（纯白底 + 黑帽）
  assets/hatmod/textures/item/{black,white,red}_hat.png                 16x16 物品图标
  assets/hatmod/textures/models/armor/{black,white,red}_layer_1.png     128x64 自定义牛仔帽模型贴图
  assets/hatmod/textures/particle/flash_light.png                       16x16 白色光斑
  assets/hatmod/textures/particle/beam.png                              32x32 光锥表面的流动纹路
  assets/hatmod/textures/particle/beam_core.png                         64x64 轴线亮芯的圆形光斑
  assets/hatmod/textures/item/tuner.png                                 16x16 调参器图标
  assets/hatmod/textures/mob_effect/veil.png                            16x16「神隐」状态图标

用法： python tools/gen_textures.py
"""
import math
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "hatmod", "textures")

TRANSPARENT = (0, 0, 0, 0)

PALETTES = {
    "black_hat": {
        "base": (44, 44, 52, 255),
        "shade": (24, 24, 30, 255),
        "light": (78, 78, 92, 255),
        "band": (104, 104, 120, 255),
    },
    "white_hat": {
        "base": (238, 238, 244, 255),
        "shade": (186, 186, 200, 255),
        "light": (255, 255, 255, 255),
        "band": (146, 146, 162, 255),
    },
    "red_hat": {
        "base": (186, 46, 44, 255),
        "shade": (116, 22, 22, 255),
        "light": (236, 100, 88, 255),
        "band": (92, 18, 18, 255),
    },
}


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


def blank(width, height):
    return [[TRANSPARENT for _ in range(width)] for _ in range(height)]


def rect(rows, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if 0 <= y < len(rows) and 0 <= x < len(rows[0]):
                rows[y][x] = color


# ----------------------------------------------------------------------
# 16x16 牛仔帽物品图标：高帽冠 + 宽帽檐 + 微微上翘的两端
# ----------------------------------------------------------------------
def build_item_icon(palette):
    rows = blank(16, 16)
    base, shade, light, band = (palette[k] for k in ("base", "shade", "light", "band"))

    # 帽冠
    rect(rows, 5, 2, 10, 9, base)
    # 冠顶高光 + 左侧高光 + 右侧暗边
    rect(rows, 5, 2, 10, 2, light)
    rect(rows, 5, 3, 5, 7, light)
    rect(rows, 10, 3, 10, 9, shade)
    # 帽带
    rect(rows, 5, 8, 10, 9, band)
    # 帽檐
    rect(rows, 1, 10, 14, 11, shade)
    rect(rows, 2, 10, 13, 10, base)
    # 两端上翘
    rows[9][1] = shade
    rows[9][14] = shade
    # 檐口加深
    rect(rows, 1, 12, 14, 12, shade)
    return rows


def build_all_item_icon():
    """「全」的物品图标：黑帽冠 + 红帽带 + 白帽檐 —— 一顶就能看出是三顶合一的。

    形状完全复用 {@link #build_item_icon}（同一个像素布局，只是分区域换色），
    所以四顶帽子在物品栏里长得一样、只差配色。
    """
    icon = build_item_icon(PALETTES["black_hat"])
    white_base, white_shade, white_light, white_band = (PALETTES["white_hat"][k]
                                                        for k in ("base", "shade", "light", "band"))
    red_base, red_shade, red_light, red_band = (PALETTES["red_hat"][k]
                                                for k in ("base", "shade", "light", "band"))

    # 帽带（y=8..9）换成红色
    rect(icon, 5, 8, 10, 9, red_base)
    rect(icon, 10, 8, 10, 9, red_shade)
    # 帽檐（y=10..12）换成白色
    rect(icon, 1, 10, 14, 11, white_shade)
    rect(icon, 2, 10, 13, 10, white_base)
    rect(icon, 1, 12, 14, 12, white_shade)
    icon[9][1] = white_band
    icon[9][14] = white_band
    return icon


# ----------------------------------------------------------------------
# 128x64 自定义牛仔帽模型贴图
#
#   crown  texOffs(0, 0)  : 7x5x7    -> 占用 x0..27,  y0..11
#   brim   texOffs(0, 32) : 20x1x20  -> footprint 80x21，占用 x0..79, y32..52
#
# 画布必须是 128x64：20 宽的帽檐 footprint 要 80 像素宽，64x32 装不下。
# 帽冠仍画在 (0,0)，texOffs 像素号没变，采样到的还是原来那几格。
# 帽檐 box 的贴图布局（宽 w、高 h、深 d）：
#   (u+d, v)         top    w x d      (u+d+w, v)      bottom w x d
#   (u, v+d)         right  d x h      (u+d, v+d)      front  w x h
#   (u+d+w, v+d)     left   d x h      (u+d+w+d, v+d)  back   w x h
# ----------------------------------------------------------------------
BRIM_U, BRIM_V = 0, 32
BRIM_W = BRIM_D = 20
BRIM_H = 1


def build_model_texture(palette):
    rows = blank(128, 64)
    base, shade, light, band = (palette[k] for k in ("base", "shade", "light", "band"))

    # 帽冠整体
    rect(rows, 0, 0, 27, 11, base)
    # 冠顶（box 的 top 面落在 (7,0)-(13,6)）
    rect(rows, 7, 0, 13, 6, light)
    # 帽带：四个侧面的最下一行 y=11
    rect(rows, 0, 11, 27, 11, band)

    # 帽檐：整块 footprint 铺 shade，顶面/底面用 base 提亮，最后一行（四条侧面）用 band 描边
    foot_w = 2 * BRIM_D + 2 * BRIM_W
    rect(rows, BRIM_U, BRIM_V, BRIM_U + foot_w - 1, BRIM_V + BRIM_D + BRIM_H - 1, shade)
    rect(rows, BRIM_U + BRIM_D, BRIM_V, BRIM_U + BRIM_D + 2 * BRIM_W - 1, BRIM_V + BRIM_D - 1, base)
    rect(rows, BRIM_U, BRIM_V + BRIM_D, BRIM_U + foot_w - 1, BRIM_V + BRIM_D, band)
    return rows


def build_all_model_texture():
    """「全」的护甲贴图：黑帽冠 + 红帽带 + 白帽檐（戴在头上的样子和物品图标一致）。"""
    rows = build_model_texture(PALETTES["black_hat"])
    white_base, white_shade, white_light, white_band = (PALETTES["white_hat"][k]
                                                        for k in ("base", "shade", "light", "band"))
    red_base, red_shade, red_light, red_band = (PALETTES["red_hat"][k]
                                                for k in ("base", "shade", "light", "band"))

    # 帽带（y=11）换红
    rect(rows, 0, 11, 27, 11, red_band)
    # 帽檐整块换白
    foot_w = 2 * BRIM_D + 2 * BRIM_W
    rect(rows, BRIM_U, BRIM_V, BRIM_U + foot_w - 1, BRIM_V + BRIM_D + BRIM_H - 1, white_shade)
    rect(rows, BRIM_U + BRIM_D, BRIM_V, BRIM_U + BRIM_D + 2 * BRIM_W - 1, BRIM_V + BRIM_D - 1, white_base)
    rect(rows, BRIM_U, BRIM_V + BRIM_D, BRIM_U + foot_w - 1, BRIM_V + BRIM_D, white_band)
    return rows


# ----------------------------------------------------------------------
# 16x16 白色散射光斑
# ----------------------------------------------------------------------
def build_tuner_icon():
    """调参器图标：深灰面板 + 滑槽 + 橙色滑块，四角螺钉。"""
    size = 16
    rows = blank(size, size)
    body = (58, 63, 68, 255)
    body_hi = (86, 92, 99, 255)
    body_lo = (38, 42, 46, 255)
    groove = (24, 27, 31, 255)
    knob = (255, 176, 32, 255)
    knob_hi = (255, 214, 128, 255)
    screw = (158, 165, 172, 255)

    rect(rows, 1, 3, 14, 12, body)
    rect(rows, 1, 3, 14, 3, body_hi)
    rect(rows, 1, 3, 1, 12, body_hi)
    rect(rows, 1, 12, 14, 12, body_lo)
    rect(rows, 14, 3, 14, 12, body_lo)

    rect(rows, 3, 7, 12, 8, groove)
    rect(rows, 9, 5, 11, 10, knob)
    rect(rows, 9, 5, 11, 5, knob_hi)

    for (sx, sy) in ((2, 4), (13, 4), (2, 11), (13, 11)):
        rows[sy][sx] = screw
    return rows


def build_veil_icon():
    """「神隐」的状态图标：一只眼睛 + 一道斜杠（“看不见”的通用记号）。

    没有这张图时状态栏会去图集里找 `hatmod:mob_effect/veil`，找不到就画成
    原版那种紫黑「缺失贴图」—— 白帽附魔「净辉」一发动，状态栏就多一格错乱贴图。
    """
    size = 16
    rows = blank(size, size)
    outline = (255, 255, 255, 255)
    iris = (150, 190, 230, 255)
    pupil = (46, 66, 92, 255)

    cy = 8.0
    for x in range(1, 15):
        half = 3.2 * math.sin(math.pi * (x - 1) / 13.0)
        y0 = int(round(cy - half))
        y1 = int(round(cy + half))
        for y in range(y0, y1 + 1):
            rows[y][x] = iris
        rows[y0][x] = outline
        rows[y1][x] = outline

    rect(rows, 7, 6, 8, 9, pupil)

    # 斜杠：左下 → 右上，压在眼睛上
    for i in range(1, 15):
        x = i
        y = 15 - i
        for (dx, dy) in ((0, 0), (1, 0), (0, 1)):
            xx, yy = x + dx, y + dy
            if 0 <= xx < size and 0 <= yy < size:
                rows[yy][xx] = outline
    return rows


def build_particle():
    size = 16
    rows = blank(size, size)
    center = (size - 1) / 2.0
    radius = size / 2.0
    for y in range(size):
        for x in range(size):
            dx = (x - center) / radius
            dy = (y - center) / radius
            dist = (dx * dx + dy * dy) ** 0.5
            if dist >= 1.0:
                continue
            falloff = (1.0 - dist * dist) ** 2
            alpha = int(255 * falloff)
            if alpha <= 0:
                continue
            tint = int(255 - 12 * dist)
            rows[y][x] = (tint, tint, 255, alpha)
    return rows


# ----------------------------------------------------------------------
# 光柱贴图
#
# 光柱用的是原版 energy_swirl 渲染类型（加色混合 ONE,ONE）。两个要点：
#   1. 加色混合下"亮度"来自颜色，不看透明度 —— 所以光斑的形状要画在 RGB 上；
#   2. 它的着色器里有一句 `if (color.a < 0.1) discard;` ——
#      形状画在 alpha 上会被整片裁掉，所以 alpha 一律给满 255。
# ----------------------------------------------------------------------
def build_beam():
    """光锥表面的流动纹路：沿 x（圆周方向）恒定，沿 y（光柱方向）一圈圈明暗。

    x 方向必须恒定，否则绕圆周铺一圈时会出现一道透明的接缝（早先那版就是，
    看着像一圈旋转的雾）。
    """
    size = 32
    rows = blank(size, size)
    for y in range(size):
        wave = 0.82 + 0.18 * (0.5 + 0.5 * math.cos(2.0 * math.pi * 4.0 * y / size))
        level = max(0, min(255, int(round(255.0 * wave))))
        for x in range(size):
            rows[y][x] = (level, level, level, 255)
    return rows


def build_beam_core():
    """轴线亮芯用的圆形光斑：从中心最亮到边缘完全透明。

    <p>形状**必须画在 alpha 通道上**，不能只画在 RGB 上。
    原因：1.20.1 的渲染管线下（着色器 JSON 带 blend）这个贴图会走**普通 alpha 混合**，
    此时 alpha≡255 意味着每个光斑都是"不透明黑边圆盘"，沿光柱排成一串就成了**一串圆球**。
    把形状放到 alpha 上（中心 1、边缘 0）后，alpha 混合下边缘透明、融成连续亮芯；
    加色混合（1.21.1）忽略 alpha、仍看 RGB，行为不变 —— 一改两全。
    """
    size = 64
    rows = blank(size, size)
    center = (size - 1) / 2.0
    radius = size / 2.0
    for y in range(size):
        for x in range(size):
            dx = (x - center) / radius
            dy = (y - center) / radius
            dist = math.sqrt(dx * dx + dy * dy)
            if dist >= 1.0:
                continue
            falloff = (1.0 - dist * dist) ** 2
            level = max(0, min(255, int(round(255.0 * falloff))))
            alpha = max(0, min(255, int(round(255.0 * falloff))))
            rows[y][x] = (level, level, level, alpha)
    return rows


def preview(rows, label):
    print(f"--- {label} ---")
    for row in rows:
        line = ""
        for (r, g, b, a) in row:
            if a == 0:
                line += "."
            elif a < 128:
                line += ":"
            else:
                lum = (r + g + b) / 3
                line += "#" if lum < 90 else ("+" if lum < 190 else "o")
        print(line)


# ----------------------------------------------------------------------
# 模组图标：纯白底 + 黑色牛仔帽
#
# 直接把 black_hat 的物品图标按最近邻放大若干倍、居中贴在纯白背景上：
# 像素画放大就該保留方块感（双线性插值只会糊成一片），也因此依旧不需要 Pillow。
# 写到 src/main/resources/hatmod_logo.png，由 mods.toml 的 logoFile 指过去。
# ----------------------------------------------------------------------
def build_mod_logo(scale=14, size=256):
    """纯白底 + 黑色牛仔帽。{@code scale} 是放大倍数，四周留白自动居中。"""
    icon = build_item_icon(PALETTES["black_hat"])
    white = (255, 255, 255, 255)
    rows = [[white for _ in range(size)] for _ in range(size)]
    offset = (size - 16 * scale) // 2
    for y in range(16):
        for x in range(16):
            pixel = icon[y][x]
            if pixel[3] == 0:
                continue  # 透明的地方留白
            for dy in range(scale):
                for dx in range(scale):
                    rows[offset + y * scale + dy][offset + x * scale + dx] = pixel
    return rows


def main():
    for name, palette in PALETTES.items():
        icon = build_item_icon(palette)
        write_png(os.path.join(ROOT, "item", f"{name}.png"), 16, 16, icon)
        write_png(os.path.join(ROOT, "models", "armor", f"{name}_layer_1.png"),
                  128, 64, build_model_texture(palette))
        preview(icon, f"{name}.png")

    # 「全」：黑冠 + 红带 + 白檐，形状复用上面那一套，只换配色
    all_icon = build_all_item_icon()
    write_png(os.path.join(ROOT, "item", "all_hat.png"), 16, 16, all_icon)
    write_png(os.path.join(ROOT, "models", "armor", "all_hat_layer_1.png"),
              128, 64, build_all_model_texture())
    preview(all_icon, "all_hat.png")

    write_png(os.path.join(ROOT, "particle", "flash_light.png"), 16, 16, build_particle())
    write_png(os.path.join(ROOT, "particle", "beam.png"), 32, 32, build_beam())
    write_png(os.path.join(ROOT, "particle", "beam_core.png"), 64, 64, build_beam_core())
    write_png(os.path.join(ROOT, "item", "tuner.png"), 16, 16, build_tuner_icon())
    write_png(os.path.join(ROOT, "mob_effect", "veil.png"), 16, 16, build_veil_icon())

    resources = os.path.normpath(os.path.join(ROOT, "..", "..", ".."))
    write_png(os.path.join(resources, "hatmod_logo.png"), 256, 256, build_mod_logo())

    print("textures written to", os.path.normpath(ROOT))


if __name__ == "__main__":
    main()
