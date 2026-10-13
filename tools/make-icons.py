#!/usr/bin/env python3
"""Generate the ERT launcher icon PNGs (needed because minSdk 24 predates adaptive icons).

Pure standard library: zlib + struct, no image dependencies. The glyph is the same "E" that
res/drawable/ic_launcher_foreground.xml draws, in the palette from ui/theme/Theme.kt:
background #0B0B10, foreground #B69CFF.

Usage: python3 tools/make-icons.py
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res")

BG = (0x0B, 0x0B, 0x10, 0xFF)
FG = (0xB6, 0x9C, 0xFF, 0xFF)

# Legacy launcher icons; adaptive icon XML covers API 26+.
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def glyph_pixels(x: int, y: int, s: int) -> bool:
    """True if (x,y) in an s-by-s image is inside the E glyph drawn in a 108x108 viewport."""
    u = x * 108.0 / s
    v = y * 108.0 / s
    # vertical spine
    if 32 <= u < 42 and 32 <= v < 76:
        return True
    # top bar
    if 42 <= u < 76 and 32 <= v < 42:
        return True
    # middle bar
    if 42 <= u < 72 and 49 <= v < 59:
        return True
    # bottom bar
    if 42 <= u < 76 and 66 <= v < 76:
        return True
    return False


def png(path: str, size: int) -> None:
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # filter type 0
        for x in range(size):
            px = FG if glyph_pixels(x, y, size) else BG
            raw.extend(px)

    def chunk(tag: bytes, data: bytes) -> bytes:
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA
    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", ihdr)
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(out)


def main() -> None:
    for density, size in SIZES.items():
        d = os.path.join(OUT, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "ic_launcher.png")
        png(p, size)
        print(f"{p} ({size}x{size})")


if __name__ == "__main__":
    main()
