"""Writes a flat, strongly saturated, colour-untagged 720x576 H.264 clip.

    python tools/make_colour_clip.py audit-colour-sd.mp4

x264 writes no colour description unless asked, so players have to guess the
matrix. mpv guesses BT.601 for SD sizes and BT.709 for HD sizes, so an
untagged SD source upscaled to an HD size without explicit tags shifts hue,
most visibly in saturated reds and greens. The colour device test compares
relayed playback of this clip against direct playback of the original.

Needs PyAV and NumPy; the relay server's virtualenv has both.
"""
import sys

import av
import numpy as np

COLOUR = (200, 60, 90)

with av.open(sys.argv[1], "w") as container:
    video = container.add_stream("libx264", rate=25)
    video.width, video.height, video.pix_fmt = 720, 576, "yuv420p"
    image = np.empty((576, 720, 3), dtype=np.uint8)
    image[:, :] = COLOUR
    for i in range(25 * 20):
        frame = av.VideoFrame.from_ndarray(image, format="rgb24")
        frame.pts = i
        for packet in video.encode(frame):
            container.mux(packet)
    for packet in video.encode():
        container.mux(packet)
