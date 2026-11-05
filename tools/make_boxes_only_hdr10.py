"""Writes an HDR10 MP4 whose static metadata only its mdcv and clli boxes carry.

    python tools/make_boxes_only_hdr10.py hdr10-container.mp4 hdr10-boxes-only.mp4

Removes every prefix SEI (NAL type 39) from an HEVC MP4: from the samples with
ffmpeg's filter_units, and from the hvcC record, which that filter leaves
alone, by dropping its SEI array here. The relay server prefers a frame's
mastering display and light level SEI to what an uplink sends, so only a file
without them shows the uplink's container metadata reaching the downlink.

The input must be an ffmpeg MP4 with its moov after its mdat (no faststart):
the hvcC shrinks, and only box sizes are fixed, not chunk offsets. Needs
ffmpeg on PATH.
"""
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

PATH = [b"moov", b"trak", b"mdia", b"minf", b"stbl", b"stsd", b"hvc1", b"hvcC"]
FIELDS_BEFORE_CHILDREN = {b"stsd": 8, b"hvc1": 78}
SEI_PREFIX = 39


def find(data: bytes, start: int, end: int, box_type: bytes) -> tuple[int, int]:
    position = start
    while position + 8 <= end:
        size, found = struct.unpack(">I4s", data[position:position + 8])
        if size < 8:
            break
        if found == box_type:
            return position, size
        position += size
    sys.exit(f"no {box_type.decode()} box")


def strip_hvcc_sei(data: bytearray) -> int:
    """Drops hvcC's prefix-SEI array in place; returns the bytes removed."""
    ancestors = []
    start, end = 0, len(data)
    for box_type in PATH:
        position, size = find(data, start, end, box_type)
        ancestors.append(position)
        start, end = position + 8 + FIELDS_BEFORE_CHILDREN.get(box_type, 0), position + size
    if find(data, 0, len(data), b"mdat")[0] > ancestors[0]:
        sys.exit("moov must follow mdat: chunk offsets would move")

    count_at = ancestors[-1] + 8 + 22
    position = count_at + 1
    removed = None
    for _ in range(data[count_at]):
        array_start = position
        nal_type = data[position] & 0x3F
        (units,) = struct.unpack(">H", data[position + 1:position + 3])
        position += 3
        for _ in range(units):
            (length,) = struct.unpack(">H", data[position:position + 2])
            position += 2 + length
        if nal_type == SEI_PREFIX:
            removed = (array_start, position)
    if removed is None:
        return 0

    cut = removed[1] - removed[0]
    data[count_at] -= 1
    del data[removed[0]:removed[1]]
    for position in ancestors:
        (size,) = struct.unpack(">I", data[position:position + 4])
        data[position:position + 4] = struct.pack(">I", size - cut)
    return cut


def main(source: str, destination: str) -> None:
    with tempfile.TemporaryDirectory() as work:
        filtered = Path(work) / "filtered.mp4"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", source, "-map", "0", "-c", "copy",
             "-bsf:v", f"filter_units=remove_types={SEI_PREFIX}", str(filtered)],
            check=True,
        )
        data = bytearray(filtered.read_bytes())
    cut = strip_hvcc_sei(data)
    Path(destination).write_bytes(data)
    print(f"{destination}: removed {cut} bytes of SEI from hvcC")


if __name__ == "__main__":
    main(*sys.argv[1:3])
