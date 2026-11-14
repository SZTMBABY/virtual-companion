"""生成 App 图标（终版）：一条圆润的小鲸，深蓝底 + 浅蓝身体。

设计取舍：
- 用「侧视的圆润小鲸」而不是抽象图形 —— 这是虚拟伴侣，图标要传递「活的、可爱的」，
  抽象符号（V 形尾鳍）在 48px 下会被读成箭头。
- 尾巴用一条连续的贝塞尔收进身体，而不是「两块拼」：拼接处一定留缝，
  小尺寸下会裂成两片树叶（试过三版都这样）。
- 所有形状先画在 4 倍画布再缩小，边缘才干净。
"""
import os

from PIL import Image, ImageDraw, ImageFilter

W = 108
NAVY_TOP = (20, 52, 78)
NAVY_MID = (12, 34, 52)
NAVY_BOT = (6, 17, 28)
BODY_TOP = (158, 220, 249)
BODY_BOT = (78, 162, 218)
BELLY = (231, 246, 255)
DARK = (9, 28, 43)
GLOW = (52, 132, 190)
SPOUT = (200, 236, 255)


def s(v, size):
    return v * size / W


def bez(p0, p1, p2, n=40):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append(
            (
                u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
            )
        )
    return out


def canvas(size):
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def rounded_mask(size, ratio=0.235):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * ratio), fill=255
    )
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def background(size):
    img = Image.new("RGBA", (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        if t < 0.5:
            k = t / 0.5
            c = [int(NAVY_TOP[i] + (NAVY_MID[i] - NAVY_TOP[i]) * k) for i in range(3)]
        else:
            k = (t - 0.5) / 0.5
            c = [int(NAVY_MID[i] + (NAVY_BOT[i] - NAVY_MID[i]) * k) for i in range(3)]
        d.line([(0, y), (size, y)], fill=tuple(c) + (255,))

    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy, r = size * 0.5, size * 0.46, size * 0.42
    gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GLOW + (80,))
    return Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(size * 0.10)))


def whale(size, mono=False):
    def P(x, y):
        return (s(x, size), s(y, size))

    img = canvas(size)
    d = ImageDraw.Draw(img)
    col = (255, 255, 255, 255) if mono else BODY_BOT + (255,)

    # 尾巴：一条连续的曲线，从身体左上绕出去再收回来
    tail = []
    tail += bez((47, 48), (34, 44), (23, 37))
    tail += bez((23, 37), (28, 47), (32, 55))
    tail += bez((32, 55), (28, 63), (22, 73))
    tail += bez((22, 73), (34, 66), (47, 61))
    d.polygon([P(x, y) for x, y in tail], fill=col)

    # 身体：圆润的水滴（右头左尾）
    body = []
    body += bez((34, 54), (44, 32), (70, 32))
    body += bez((70, 32), (94, 34), (95, 55))
    body += bez((95, 55), (96, 76), (72, 80))
    body += bez((72, 80), (46, 82), (34, 54))
    d.polygon([P(x, y) for x, y in body], fill=col)

    body_mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(body_mask).polygon([P(x, y) for x, y in body], fill=255)

    if not mono:
        # 上半身受光
        hi = canvas(size)
        ImageDraw.Draw(hi).ellipse([P(34, 32), P(96, 62)], fill=BODY_TOP + (255,))
        hi = hi.filter(ImageFilter.GaussianBlur(size * 0.018))
        img = Image.alpha_composite(img, Image.composite(hi, canvas(size), body_mask))
        d = ImageDraw.Draw(img)

        # 肚皮
        belly = canvas(size)
        ImageDraw.Draw(belly).ellipse([P(44, 60), P(92, 84)], fill=BELLY + (255,))
        img = Image.alpha_composite(img, Image.composite(belly, canvas(size), body_mask))
        d = ImageDraw.Draw(img)

    # 眼睛
    if mono:
        d.ellipse([P(76, 46), P(86, 56)], fill=(255, 255, 255, 255))
    else:
        d.ellipse([P(76, 46), P(86, 56)], fill=DARK + (255,))
        d.ellipse([P(78, 48), P(81, 51)], fill=(255, 255, 255, 245))

    if not mono:
        # 腮红
        blush = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ImageDraw.Draw(blush).ellipse(
            [P(68, 52), P(78, 58)], fill=(255, 138, 164, 105)
        )
        img = Image.alpha_composite(img, blush.filter(ImageFilter.GaussianBlur(size * 0.01)))
        d = ImageDraw.Draw(img)

        # 嘴：上翘的小弧
        d.arc(
            [P(80, 56), P(92, 68)],
            start=12,
            end=150,
            fill=DARK + (215,),
            width=max(1, int(size * 0.011)),
        )

        # 喷的水花
        for cx, cy, r in ((66, 24, 2.6), (73, 18, 1.9), (59, 19, 1.7)):
            d.ellipse([P(cx - r, cy - r), P(cx + r, cy + r)], fill=SPOUT + (225,))

        # 泡泡
        for cx, cy, r in ((99, 34, 2.6), (103, 25, 1.7)):
            d.ellipse(
                [P(cx - r, cy - r), P(cx + r, cy + r)],
                outline=(152, 212, 246, 180),
                width=max(1, int(size * 0.0055)),
            )

    return img


def legacy(size, round_=False):
    big = size * 2
    out = Image.alpha_composite(background(big), whale(big))
    out.putalpha(circle_mask(big) if round_ else rounded_mask(big))
    return out.resize((size, size), Image.LANCZOS)


def foreground(size, mono=False):
    """自适应图标前景：整个图案必须落在中间 66dp 的安全区里，
    否则圆形/水滴形裁剪会把尾巴和泡泡切掉（这份画布是 108dp）。"""
    big = whale(size * 2, mono=mono)
    # 按实际像素的包围盒裁出来再居中：鲸不在画布正中央，直接缩整张会偏右。
    bbox = big.split()[3].getbbox()
    big = big.crop(bbox)
    inner = int(round(size * 0.72))
    w, h = big.size
    scale = inner / max(w, h)
    big = big.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(big, ((size - big.width) // 2, (size - big.height) // 2))
    return out


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def main():
    root = os.path.join("app", "src", "main", "res")
    for name, mul in DENSITIES.items():
        fg_px = int(108 * mul)
        d_fg = os.path.join(root, "drawable-" + name)
        os.makedirs(d_fg, exist_ok=True)
        foreground(fg_px).save(os.path.join(d_fg, "ic_launcher_foreground.png"))
        foreground(fg_px, mono=True).save(os.path.join(d_fg, "ic_launcher_monochrome.png"))

        legacy_px = int(48 * mul)
        d_mip = os.path.join(root, "mipmap-" + name)
        os.makedirs(d_mip, exist_ok=True)
        legacy(legacy_px).save(os.path.join(d_mip, "ic_launcher.webp"), lossless=True)
        legacy(legacy_px, round_=True).save(
            os.path.join(d_mip, "ic_launcher_round.webp"), lossless=True
        )

    prev = Image.new("RGBA", (620, 220), (11, 22, 33, 255))
    prev.alpha_composite(legacy(176), (16, 22))
    prev.alpha_composite(legacy(176, round_=True), (206, 22))
    prev.alpha_composite(legacy(48), (400, 26))
    prev.alpha_composite(legacy(48, round_=True), (400, 92))
    prev.alpha_composite(legacy(96), (470, 26))
    prev.save(os.path.join(".tmp_probe", "icon_preview.png"))
    print("done")


if __name__ == "__main__":
    main()
