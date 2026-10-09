"""Writes the 720x576, 16:15 (PAL 4:3) test clips for the pixel-aspect device test.

    python tools/make_anamorphic_clips.py <output directory>

- audit-sar-16x15.mp4: MP4, aspect in the container; MediaExtractor reports it.
- audit-sar-16x15.mkv: Matroska as FFmpeg writes it without a stream aspect
  (DisplayUnit 4), leaving the aspect to the bitstream only.
- audit-sar-16x15-display.mkv: the same with DisplayWidth/DisplayHeight
  768x576, as mkvmerge writes it. SeekHead, Cues, Tags and the stale CRC are
  dropped so no offset can go stale; the file stays valid but unindexed.

Needs PyAV and NumPy; the relay server's virtualenv has both.
"""
import os
import sys
from fractions import Fraction

import av
import numpy as np

DROPPED = {0x114D9B74, 0x1C53BB6B, 0x1254C367, 0xEC}  # SeekHead, Cues, Tags, Void


def encode(path: str) -> None:
    with av.open(path, "w") as container:
        video = container.add_stream("libx264", rate=25)
        video.width, video.height, video.pix_fmt = 720, 576, "yuv420p"
        video.codec_context.sample_aspect_ratio = Fraction(16, 15)
        video.sample_aspect_ratio = Fraction(16, 15)
        for i in range(250):
            frame = av.VideoFrame.from_ndarray(
                np.full((576, 720, 3), (i * 3) % 256, dtype=np.uint8), format="rgb24",
            )
            frame.pts = i
            for packet in video.encode(frame):
                container.mux(packet)
        for packet in video.encode():
            container.mux(packet)


def elements(data: bytes):
    """(id, id bytes, payload) for each element in data."""
    def vint(pos: int, keep: bool):
        first = data[pos]
        if first == 0:
            raise ValueError("invalid EBML vint")
        length = 1
        while not first & (0x80 >> (length - 1)):
            length += 1
        value = first if keep else first & (0xFF >> length)
        for byte in data[pos + 1:pos + length]:
            value = (value << 8) | byte
        return value, length

    pos = 0
    while pos < len(data):
        element_id, id_length = vint(pos, True)
        size, size_length = vint(pos + id_length, False)
        body = pos + id_length + size_length
        if size == (1 << (7 * size_length)) - 1:
            size = len(data) - body  # unknown size: runs to the end
        yield element_id, data[pos:pos + id_length], data[body:body + size]
        pos = body + size


def element(id_bytes: bytes, payload: bytes) -> bytes:
    return id_bytes + bytes([0x01]) + len(payload).to_bytes(7, "big") + payload


def uint(value: int) -> bytes:
    return value.to_bytes(max(1, (value.bit_length() + 7) // 8), "big")


def with_display_size(source: str, target: str, width: int, height: int) -> None:
    def rewrite(data: bytes, depth: int) -> bytes:
        out = b""
        for element_id, id_bytes, payload in elements(data):
            if depth == 0 and element_id in DROPPED:
                continue
            if element_id == 0x1654AE6B or element_id == 0xAE:  # Tracks, TrackEntry
                children = rewrite(payload, depth + 1)
            elif element_id == 0xE0:  # Video
                kept = b"".join(
                    element(i, p) for e, i, p in elements(payload) if e not in (0x54B0, 0x54BA, 0x54B2)
                )
                children = kept + element(b"\x54\xb0", uint(width)) + element(b"\x54\xba", uint(height))
            elif depth > 0 and element_id == 0xBF:  # CRC-32 of an element this edits
                continue
            else:
                children = payload
            out += element(id_bytes, children)
        return out

    data = open(source, "rb").read()
    (_, header_id, header), (_, segment_id, segment) = list(elements(data))[:2]
    with open(target, "wb") as out:
        out.write(element(header_id, header) + element(segment_id, rewrite(segment, 0)))


def main() -> None:
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    mp4 = os.path.join(directory, "audit-sar-16x15.mp4")
    mkv = os.path.join(directory, "audit-sar-16x15.mkv")
    encode(mp4)
    encode(mkv)
    with_display_size(mkv, os.path.join(directory, "audit-sar-16x15-display.mkv"), 768, 576)


if __name__ == "__main__":
    main()
