"""
Иконки «Хватит.Почты» из одной картинки: branding/icon-source.png (квадрат, «запечённый» скруглённый
квадрат на белом — формат iOS). Android рисует форму сам, поэтому:

  adaptive (Android 8+): слой-картинка 108dp во весь кадр, форму (круг/сквирк/капля) накладывает лаунчер.
      Видна середина 72dp, гарантированно — круг 66dp: конверт (с ореолом) уменьшен так, чтобы
      диагональ влезала в 66dp при любой маске. Рамку iOS вырезаем, края дозаливаем тёмным фоном.
  legacy (Android 5–7): готовый скруглённый квадрат 48dp с прозрачными углами.
  play: 512×512, полный квадрат без прозрачности (маску магазин накладывает сам).

Значок уведомления и монохромный слой — векторы в res/drawable (белый силуэт конверта), не отсюда.

Запуск: py -3 branding/make_icons.py   (из папки почта/)
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "branding" / "icon-source.png"
RES = ROOT / "Pochta" / "app" / "src" / "main" / "res"
OUT = ROOT / "branding"

# замеры исходника 1254×1254 (15.11.2026)
ENV_CX, ENV_CY = 623, 623        # центр конверта
ENV_W = 642                      # ширина конверта без ореола
FRAME_INSET = 62                 # где кончаются рамка и стеклянный ободок
FRAME_RADIUS = 210               # радиус скругления внутреннего края рамки
FEATHER = 36                     # мягкость шва картинки с заливкой
FILL = (19, 23, 26)              # цвет фона у края картинки — им дозаливаем

ENV_DP = 50                      # ширина конверта в адаптивной иконке (из 108dp): диагональ ≈ 61dp < 66dp


def inner_mask(size: int) -> Image.Image:
    """Маска картинки без рамки iOS: скруглённый квадрат с мягким краем."""
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        (FRAME_INSET, FRAME_INSET, size - 1 - FRAME_INSET, size - 1 - FRAME_INSET),
        radius=FRAME_RADIUS - FRAME_INSET // 2, fill=255)
    return m.filter(ImageFilter.GaussianBlur(FEATHER / 2))


def full_bleed(src: Image.Image) -> Image.Image:
    """Квадрат 108dp: картинка без рамки, конверт ENV_DP шириной, по центру; края дозалиты фоном."""
    side = round(ENV_W * 108 / ENV_DP)
    canvas = Image.new("RGB", (side, side), FILL)
    ox, oy = side // 2 - ENV_CX, side // 2 - ENV_CY
    canvas.paste(src, (ox, oy), inner_mask(src.width))
    return canvas


def legacy(src: Image.Image, px: int) -> Image.Image:
    """Android 5–7: скруглённый квадрат с прозрачными углами, поле 1/12 как у системных иконок."""
    art = full_bleed(src)
    # видимая часть как у адаптивной: середина 72/108, но чуть шире, чтобы карточки читались
    s = art.width
    crop = art.crop((s * 16 // 108, s * 16 // 108, s * 92 // 108, s * 92 // 108))
    body = round(px * 11 / 12)
    crop = crop.resize((body, body), Image.LANCZOS)
    mask = Image.new("L", (body * 4, body * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, body * 4 - 1, body * 4 - 1), radius=body * 4 * 22 // 100, fill=255)
    mask = mask.resize((body, body), Image.LANCZOS)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.paste(crop, ((px - body) // 2, (px - body) // 2), mask)
    return out


def main():
    src = Image.open(SRC).convert("RGB")
    art = full_bleed(src)
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for d, k in dens.items():
        folder = RES / f"mipmap-{d}"
        folder.mkdir(parents=True, exist_ok=True)
        art.resize((round(108 * k), round(108 * k)), Image.LANCZOS).save(folder / "ic_launcher_foreground.png", optimize=True)
        lg = legacy(src, round(48 * k))
        lg.save(folder / "ic_launcher.png", optimize=True)
        lg.save(folder / "ic_launcher_round.png", optimize=True)
    # магазин: 512, полный квадрат — видимая середина адаптивной иконки (чуть шире)
    s = art.width
    play = art.crop((s * 14 // 108, s * 14 // 108, s * 94 // 108, s * 94 // 108)).resize((512, 512), Image.LANCZOS)
    play.save(OUT / "play-icon-512.png", optimize=True)
    # предпросмотр масок: круг, сквирк, капля — посмотреть глазами
    prev = Image.new("RGB", (3 * 220 + 40, 260), (240, 240, 240))
    a = art.resize((300, 300), Image.LANCZOS).crop((50, 50, 250, 250))   # видимые 72dp из 108
    for i, shape in enumerate(("circle", "squircle", "drop")):
        m = Image.new("L", (800, 800), 0)
        dr = ImageDraw.Draw(m)
        if shape == "circle":
            dr.ellipse((0, 0, 799, 799), fill=255)
        elif shape == "squircle":
            dr.rounded_rectangle((0, 0, 799, 799), radius=240, fill=255)
        else:
            dr.rounded_rectangle((0, 0, 799, 799), radius=400, fill=255)
            dr.rectangle((400, 400, 799, 799), fill=255)
        m = m.resize((200, 200), Image.LANCZOS)
        prev.paste(a, (20 + i * 220, 30), m)
    prev.save(OUT / "preview-masks.png")
    print("ok:", art.size, "->", RES)


if __name__ == "__main__":
    main()
