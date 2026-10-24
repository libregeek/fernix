#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerates the small audio fixtures in app/src/androidTest/assets.

Needs ffmpeg (with libmp3lame, libvorbis, libopus, flac) and the Python package mutagen.
The tests assert the exact tag values written here, so change both together.

    python3 scripts/generate-test-fixtures.py [output-dir]
    python3 scripts/generate-test-fixtures.py --art-only [output-dir]   # only art.*, from the existing samples
"""
import base64
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

from mutagen.flac import FLAC, Picture
from mutagen.id3 import APIC, ID3, TALB, TCOM, TCON, TDRC, TEXT, TIT2, TPE1, TRCK
from mutagen.oggvorbis import OggVorbis

ART_ONLY = "--art-only" in sys.argv
_ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
OUT = Path(_ARGS[0]) if _ARGS else Path(__file__).resolve().parent.parent / "app/src/androidTest/assets"


def png_1x1() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(b"\x00\xc8\x64\x32"))
        + chunk(b"IEND", b"")
    )


def png_solid(rgb: tuple) -> bytes:
    """A 4x4 PNG of one colour, so tests can tell the pictures apart by their bytes."""

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    rows = b"".join(b"\x00" + bytes(rgb) * 4 for _ in range(4))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 4, 4, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


RED, GREEN, BLUE = png_solid((255, 0, 0)), png_solid((0, 255, 0)), png_solid((0, 0, 255))


def flac_picture(kind: int, desc: str, data: bytes) -> Picture:
    p = Picture()
    p.type = kind
    p.mime = "image/png"
    p.desc = desc
    p.width = p.height = 4
    p.depth = 24
    p.data = data
    return p


def art_fixtures(source: Path, out: Path) -> None:
    """art.mp3/.flac/.ogg: the samples with their pictures replaced by known ones (tests read these).

    mp3: back cover (green), front cover (red), composer "Johnson" (blue), in this order.
    flac: front cover (red), artist "Ann" (blue).
    ogg: lead artist "Ann" (blue) only, so the first picture is the cover.
    """
    mp3 = out / "art.mp3"
    mp3.write_bytes((source / "sample.mp3").read_bytes())
    t = ID3(mp3)
    t.delall("APIC")
    t.add(APIC(encoding=3, mime="image/png", type=4, desc="back", data=GREEN))
    t.add(APIC(encoding=3, mime="image/png", type=3, desc="front", data=RED))
    t.add(APIC(encoding=3, mime="image/png", type=11, desc="Johnson", data=BLUE))
    t.save(mp3, v2_version=4)

    flac = out / "art.flac"
    flac.write_bytes((source / "sample.flac").read_bytes())
    f = FLAC(flac)
    f.clear_pictures()
    f.add_picture(flac_picture(3, "front", RED))
    f.add_picture(flac_picture(8, "Ann", BLUE))
    f.save()

    ogg = out / "art.ogg"
    ogg.write_bytes((source / "sample.ogg").read_bytes())
    o = OggVorbis(ogg)
    o.pop("metadata_block_picture", None)
    o["METADATA_BLOCK_PICTURE"] = [base64.b64encode(flac_picture(7, "Ann", BLUE).write()).decode("ascii")]
    o.save()


def picture() -> Picture:
    p = Picture()
    p.type = 3  # front cover
    p.mime = "image/png"
    p.desc = "cover"
    p.width = p.height = 1
    p.depth = 24
    p.data = png_1x1()
    return p


def ffmpeg(dest: Path, *codec_args: str, seconds: int = 2, rate: int = 22050) -> None:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
         "-ar", str(rate), "-ac", "1", "-map_metadata", "-1", *codec_args, str(dest)],
        check=True,
    )


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    if ART_ONLY:
        art_fixtures(OUT, OUT)
        return
    with tempfile.TemporaryDirectory() as tmp_name:
        tmp = Path(tmp_name)

        # MP3: ID3v2.4 with null-separated multi-value ARTIST.
        mp3 = tmp / "sample.mp3"
        ffmpeg(mp3, "-c:a", "libmp3lame", "-b:a", "32k", "-write_id3v2", "0", "-write_xing", "0")
        tags = ID3()
        tags.add(TIT2(encoding=3, text=["Mp3 Song"]))
        tags.add(TPE1(encoding=3, text=["Shared Artist", "Second Artist"]))
        tags.add(TALB(encoding=3, text=["Mp3 Album"]))
        tags.add(TDRC(encoding=3, text=["1996"]))
        tags.add(TCON(encoding=3, text=["Soundtrack"]))
        tags.add(TCOM(encoding=3, text=["Mp3 Composer"]))
        tags.add(TEXT(encoding=3, text=["Mp3 Lyricist"]))
        tags.add(TRCK(encoding=3, text=["3/12"]))
        tags.add(APIC(encoding=3, mime="image/png", type=3, desc="cover", data=png_1x1()))
        tags.save(mp3, v2_version=4)

        # Ogg Vorbis: custom tags and an embedded picture.
        ogg = tmp / "sample.ogg"
        ffmpeg(ogg, "-c:a", "libvorbis", "-q:a", "0")
        v = OggVorbis(ogg)
        v["TITLE"] = ["Ogg Song"]
        v["ARTIST"] = ["Ogg Artist"]
        v["ALBUM"] = ["Ogg Album"]
        v["COMPOSER"] = ["Ogg Composer"]
        v["LYRICIST"] = ["Ogg Lyricist"]
        v["LANGUAGE"] = ["Testish"]
        v["MUSICBRAINZ_DISCID"] = ["TestDiscId-0123456789"]
        v["METADATA_BLOCK_PICTURE"] = [base64.b64encode(picture().write()).decode("ascii")]
        v.save()

        # FLAC: multi-value GENRE, shared artist, custom tags, embedded picture.
        flac = tmp / "sample.flac"
        ffmpeg(flac, "-c:a", "flac")
        f = FLAC(flac)
        f["TITLE"] = ["Flac Song"]
        f["ARTIST"] = ["Shared Artist"]
        f["ALBUMARTIST"] = ["Various Artists"]
        f["ALBUM"] = ["Flac Album"]
        f["DATE"] = ["2011-12-01"]
        f["GENRE"] = ["Asian Music", "Indian Music"]
        f["BPM"] = ["111"]
        f["ISRC"] = ["TEST00000001"]
        f.add_picture(picture())
        f.save()

        # Opus and WAV: the formats the tag tests also cover.
        opus = tmp / "sample.opus"
        ffmpeg(opus, "-c:a", "libopus", "-b:a", "24k",
               "-metadata", "title=Opus Song", "-metadata", "artist=Opus Artist",
               "-metadata", "album=Opus Album", "-metadata", "date=2020", "-metadata", "composer=Opus Composer",
               rate=24000)
        wav = tmp / "sample.wav"
        ffmpeg(wav, "-c:a", "pcm_s16le", seconds=1, rate=8000)
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-c", "copy",
             "-metadata", "title=Wav Song", "-metadata", "artist=Wav Artist",
             "-metadata", "album=Wav Album", "-metadata", "date=2019", str(tmp / "tagged.wav")],
            check=True,
        )
        (tmp / "tagged.wav").replace(wav)

        # Hi-res sources for the audio-path test: 24-bit FLAC at 96 kHz and 24-bit WAV at 44.1 kHz, both stereo.
        hires_flac = tmp / "hires24.flac"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=96000",
             "-ac", "2", "-sample_fmt", "s32", "-c:a", "flac", "-bits_per_raw_sample", "24", "-map_metadata", "-1", str(hires_flac)],
            check=True,
        )
        hires_wav = tmp / "hires24.wav"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=44100",
             "-ac", "2", "-c:a", "pcm_s24le", "-map_metadata", "-1", str(hires_wav)],
            check=True,
        )

        for path in (mp3, ogg, flac, opus, wav, hires_flac, hires_wav):
            (OUT / path.name).write_bytes(path.read_bytes())
            print(f"{path.name}: {path.stat().st_size} bytes")
        art_fixtures(OUT, OUT)


if __name__ == "__main__":
    main()
