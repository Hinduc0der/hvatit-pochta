"""
Замена надписи на карточке «Яндекс 360» (товарный знак) на «IMAP 360 ₽» — намёк на платный IMAP
и подписку. Запускалось один раз по исходной картинке из git (коммит до 0.2.2):
  py -3 branding/retext_card.py <исходник> branding/icon-source.png "IMAP 360 ₽" 46
"""
import sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
src, out, text = sys.argv[1], sys.argv[2], sys.argv[3]
im = np.asarray(Image.open(src).convert('RGB')).astype(np.float32)
ANG = 9.6  # наклон карточки, градусы по часовой
# 1) область надписи — повёрнутый прямоугольник вокруг «Яндекс 360»
poly = [(838,192),(1112,238),(1100,302),(826,256)]
area = Image.new('L', (im.shape[1], im.shape[0]), 0)
ImageDraw.Draw(area).polygon(poly, fill=255)
area = np.asarray(area) > 0
lum = im.mean(axis=2)
# фон карточки там ~30–45, буквы ярче
text_mask = area & (lum > 52)
m = Image.fromarray((text_mask*255).astype(np.uint8)).filter(ImageFilter.MaxFilter(7))
hole = np.asarray(m) > 0
# 2) заделываем дырку: нормированная свёртка, несколько проходов
known = (~hole).astype(np.float32)
fill = im.copy()
for r in (3, 6, 12, 24):
    w = np.asarray(Image.fromarray((known*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r))).astype(np.float32)/255
    acc = np.stack([np.asarray(Image.fromarray((fill[...,c]*known).clip(0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r))).astype(np.float32) for c in range(3)], -1)
    est = acc / np.maximum(w[...,None], 1e-3)
    upd = hole & (w > 0.05) & (known < 1)
    fill[upd] = est[upd]
    known[upd] = 1
fill[hole] = np.asarray(Image.fromarray(fill.clip(0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2)))[hole]
base = Image.fromarray(fill.clip(0,255).astype(np.uint8))
# 3) новая надпись тем же серым, с тем же наклоном и мягкостью
font = ImageFont.truetype(r'C:\Windows\Fonts\segoeuib.ttf', int(sys.argv[4]) if len(sys.argv)>4 else 50)
L = Image.new('L', (900, 300), 0)
ImageDraw.Draw(L).text((50, 100), text, font=font, fill=255)
L = L.rotate(-ANG, resample=Image.BICUBIC, center=(50, 100+font.getmetrics()[0]))
L = L.filter(ImageFilter.GaussianBlur(1.1))
ox, oy = 845-50, 238-(100+font.getmetrics()[0])   # левый край базовой линии
color = Image.new('RGB', L.size, (108, 112, 115))
base.paste(color, (ox, oy), L)
base.save(out)
c = base.crop((640,110,1170,370)).resize((1060,520)); c.save(out.replace('.png','-crop.png'))
