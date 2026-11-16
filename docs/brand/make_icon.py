# Icona originale di Vigilo: punto rosso luminoso (come la Sentinella attiva) con anello bianco sottile,
# su fondo scuro. Non deriva da asset Tesla. Uso: cd docs/brand && python3 make_icon.py
# La stessa geometria (viewport 108: anello r=29, spessore 2.2; punto r=15; alone r=27) è nel
# foreground adattivo Android (app/src/main/res/drawable/ic_launcher_foreground.xml).
from PIL import Image, ImageDraw, ImageFilter, ImageFont

BG = (11, 11, 12)
RED = (255, 69, 58)

def render(size, rounded=True):
    S = size * 4
    k = S / 108.0          # stessa geometria del viewport Android, scalata
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if rounded:
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=BG + (255,))
    else:
        d.rectangle([0, 0, S, S], fill=BG + (255,))
    c = S / 2
    # alone rosso morbido
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    r = 21 * k
    gd.ellipse([c - r, c - r, c + r, c + r], fill=RED + (170,))
    glow = glow.filter(ImageFilter.GaussianBlur(7 * k))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)
    # punto rosso pieno, centro più chiaro
    r = 15 * k
    d.ellipse([c - r, c - r, c + r, c + r], fill=RED + (255,))
    hi = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hi)
    hr = 8 * k
    hd.ellipse([c - hr, c - hr, c + hr, c + hr], fill=(255, 120, 110, 150))
    hi = hi.filter(ImageFilter.GaussianBlur(4 * k))
    img = Image.alpha_composite(img, hi)
    d = ImageDraw.Draw(img)
    # anello bianco sottile
    R, w = 29 * k, 2.2 * k
    d.ellipse([c - R - w / 2, c - R - w / 2, c + R + w / 2, c + R + w / 2], outline=(255, 255, 255, 255), width=int(round(w)))
    if rounded:
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
        img.putalpha(Image.composite(img.getchannel("A"), Image.new("L", (S, S), 0), mask))
    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    render(512).save("vigilo-icon-512.png")
    render(256).save("../../addon/vigilo/icon.png")
    logo = Image.new("RGBA", (500, 140), (0, 0, 0, 0))
    logo.paste(render(128), (6, 6))
    d = ImageDraw.Draw(logo)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 64)
    except OSError:
        f = ImageFont.load_default()
    d.text((160, 32), "Vigilo", font=f, fill=(140, 140, 146, 255))
    logo.save("../../addon/vigilo/logo.png")
    print("ok")
