"""Draw Android 4-compatible widget controls and picker previews without font icons."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
ACCENT = "#d9b982"


def icon(kind, size=96):
    image = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(image)
    scale = size / 32
    def points(values):
        return [(int(x * scale), int(y * scale)) for x, y in values]
    if kind == "play":
        draw.polygon(points([(10, 5), (27, 16), (10, 27)]), fill="white")
    elif kind == "pause":
        draw.rounded_rectangle((8*scale, 5*scale, 13*scale, 27*scale), radius=scale, fill="white")
        draw.rounded_rectangle((20*scale, 5*scale, 25*scale, 27*scale), radius=scale, fill="white")
    else:
        draw.polygon(points([(6, 5), (23, 16), (6, 27)]), fill="white")
        draw.rectangle((24*scale, 5*scale, 27*scale, 27*scale), fill="white")
        if kind == "previous":
            image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    return image


def main():
    icons = ROOT / "drawable-xhdpi"
    icons.mkdir(parents=True, exist_ok=True)
    for kind in ("play", "pause", "previous", "next"):
        icon(kind).save(icons / f"widget_{kind}.png")
    previews = ROOT / "drawable-nodpi"
    previews.mkdir(parents=True, exist_ok=True)
    font_path = "C:/Windows/Fonts/msyh.ttc"
    def font(size):
        return ImageFont.truetype(font_path, size)
    for kind, size in (("tall", (420, 720)), ("card", (520, 350)), ("compact", (620, 110))):
        image = Image.new("RGBA", size)
        draw = ImageDraw.Draw(image)
        draw.rounded_rectangle((0, 0, size[0]-1, size[1]-1), radius=24, fill="#211e19")
        def text(x, y, value, size=20, color="#f4f0e8", anchor=None):
            draw.text((x, y), value, font=font(size), fill=color, anchor=anchor)
        if kind == "tall":
            text(24, 18, "NoMusic", 18, "#c2b8aa")
            text(340, 18, "K 歌", 18)
            cover = (126, 70, 294, 238)
            text(210, 258, "沿途的旋律", 28, anchor="mt")
            text(210, 304, "NoMusic · 随心聆听", 18, "#c2b8aa", "mt")
            text(210, 382, "每段旅途都有歌", 21, "#c2b8aa", "mt")
            text(210, 433, "让旋律陪你出发", 30, ACCENT, "mt")
            text(210, 486, "Let the music take you home", 17, "#c2b8aa", "mt")
            text(210, 548, "下一站，继续聆听", 21, "#c2b8aa", "mt")
        elif kind == "card":
            cover = (22, 22, 94, 94)
            text(112, 24, "沿途的旋律", 25)
            text(112, 66, "NoMusic · 随心聆听", 17, "#c2b8aa")
            text(434, 28, "K 歌", 18)
            text(24, 126, "让旋律陪你出发", 28, ACCENT)
            text(24, 174, "Let the music take you home", 18, "#c2b8aa")
            text(24, 220, "下一站，继续聆听", 18, "#c2b8aa")
        else:
            cover = (14, 18, 88, 92)
            text(108, 18, "沿途的旋律", 24)
            text(108, 60, "NoMusic · 随心聆听", 17, "#c2b8aa")
        draw.rounded_rectangle(cover, radius=10, fill="#4e4233")
        cx, cy = (cover[0]+cover[2])/2, (cover[1]+cover[3])/2
        radius = (cover[2]-cover[0])*.35
        draw.ellipse((cx-radius, cy-radius, cx+radius, cy+radius), fill="#211e19", outline=ACCENT, width=2)
        draw.ellipse((cx-5, cy-5, cx+5, cy+5), fill=ACCENT)
        if kind != "compact":
            draw.rounded_rectangle((24, size[1]-94, size[0]-24, size[1]-91), radius=1, fill="#534a3c")
            draw.rounded_rectangle((24, size[1]-94, size[0]*.45, size[1]-91), radius=1, fill=ACCENT)
        y = size[1]-68 if kind != "compact" else 30
        centers = [size[0]//2-76, size[0]//2, size[0]//2+76] if kind != "compact" else [414, 482, 550]
        for center, name in zip(centers, ("previous", "play", "next")):
            control = icon(name, 36)
            control.putdata([(217, 185, 130, pixel[3]) for pixel in control.getdata()])
            image.alpha_composite(control, (center-18, y))
        image.save(previews / f"widget_preview_{kind}.png")


if __name__ == "__main__":
    main()
