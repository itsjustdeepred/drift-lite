"""Generates the Android Auto card covers (Drift style: aurora + big title).
Titles are baked into the images, so each language gets its own folder: English in drawable-nodpi,
Italian in drawable-it-nodpi (Android picks it on Italian phones).
Usage: python3 tools/aa_tiles.py"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import pathlib, textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/tempus/res/drawable-nodpi"
OUT_IT = ROOT / "app/src/tempus/res/drawable-it-nodpi"
FONT = ROOT / "app/src/main/res/font/inter_extra_bold.ttf"
S = 480
BG = (11, 11, 15)

# name: (English title, Italian title, color 1, color 2)
TILES = {
    "ic_aa_quickmix": ("Drift Radio", "Drift Radio", "#1FDF64", "#22D3EE"),
    "ic_aa_mymix": ("Your mix", "Il tuo mix", "#8B5CF6", "#EC4899"),
    "ic_aa_discoverymix": ("Discoveries", "Scoperte", "#F59E0B", "#EF4444"),
    "ic_aa_downloaded": ("Downloaded", "Scaricati", "#14B8A6", "#1FDF64"),
    "ic_aa_albums": ("Albums", "Album", "#3B82F6", "#22D3EE"),
    "ic_aa_artists": ("Artists", "Artisti", "#A855F7", "#6366F1"),
    "ic_aa_mostplayed": ("Most played", "I più ascoltati", "#EF4444", "#F97316"),
    "ic_aa_added_album": ("Recently added", "Aggiunti di recente", "#22C55E", "#84CC16"),
    "ic_aa_added_title": ("Added songs", "Brani aggiunti", "#10B981", "#22D3EE"),
    "ic_aa_recent_title": ("Recently played", "Ascoltati di recente", "#6366F1", "#0EA5E9"),
    "ic_aa_title": ("Songs", "Brani", "#4F46E5", "#8B5CF6"),
    "ic_aa_bundle_star": ("Favorites", "Preferiti", "#EAB308", "#F97316"),
    "ic_aa_star_album": ("Favorite albums", "Album preferiti", "#F59E0B", "#EAB308"),
    "ic_aa_star_title": ("Favorite songs", "Brani preferiti", "#EAB308", "#EC4899"),
    "ic_aa_folders": ("Folders", "Cartelle", "#64748B", "#3B82F6"),
    "ic_aa_random": ("Shuffle", "Casuale", "#F97316", "#EC4899"),
    "ic_aa_genres": ("Genres", "Generi", "#D946EF", "#8B5CF6"),
    "ic_aa_podcasts": ("Podcasts", "Podcast", "#7C3AED", "#4F46E5"),
    "ic_aa_radio": ("Radio", "Radio", "#06B6D4", "#3B82F6"),
}

def hexc(h): return tuple(int(h[i:i+2], 16) for i in (1, 3, 5))

def blob(img, center, radius, color, alpha):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = center
    d.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color + (alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(radius * 0.55))
    img.alpha_composite(layer)

def logo(d, x, y, s, fill):
    w = max(2, s // 6)
    d.ellipse((x, y + s * 0.35, x + s * 0.62, y + s * 0.97), outline=fill, width=w)
    d.line((x + s * 0.62, y + s * 0.66, x + s * 0.62, y + s * 0.1), fill=fill, width=w)
    d.arc((x + s * 0.62, y, x + s * 1.02, y + s * 0.3), 180, 300, fill=fill, width=w)

def tile(name, title, c1, c2, out):
    img = Image.new("RGBA", (S, S), BG + (255,))
    blob(img, (S * 0.25, S * 0.2), S * 0.55, hexc(c1), 235)
    blob(img, (S * 0.85, S * 0.75), S * 0.5, hexc(c2), 215)
    # dark veil at the bottom so the title is readable
    veil = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    vd = ImageDraw.Draw(veil)
    for i in range(S):
        a = int(max(0, (i - S * 0.35) / (S * 0.65)) * 170)
        vd.line((0, i, S, i), fill=BG + (a,))
    img.alpha_composite(veil)
    d = ImageDraw.Draw(img)
    logo(d, 30, 28, 44, (255, 255, 255, 235))
    size = 74 if len(title) <= 10 else 62
    font = ImageFont.truetype(str(FONT), size)
    lines = textwrap.wrap(title, width=11 if size == 74 else 12)
    lh = int(size * 1.05)
    y = S - 34 - lh * len(lines)
    for ln in lines:
        d.text((30, y), ln, font=font, fill=(255, 255, 255, 255))
        y += lh
    img.convert("RGB").save(out / f"{name}.png", optimize=True)

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    OUT_IT.mkdir(parents=True, exist_ok=True)
    for n, (en, it, a, b) in TILES.items():
        tile(n, en, a, b, OUT)
        tile(n, it, a, b, OUT_IT)
        x = ROOT / f"app/src/tempus/res/drawable/{n}.xml"
        if x.exists():
            x.unlink()
    print(len(TILES), "cards generated per language")
