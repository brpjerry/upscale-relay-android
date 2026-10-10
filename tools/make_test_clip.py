"""Writes a small H.264/AAC MP4 test clip: a moving gradient and a sine tone.

The local-file device tests use a 150 s one (see DEVELOPMENT.md):

    python tools/make_test_clip.py audit-clip-150s.mp4 150

Needs PyAV and NumPy; the relay server's virtualenv has both.
"""
import sys, math
import numpy as np
import av

out, seconds = sys.argv[1], int(sys.argv[2])
fps, w, h, rate = 10, 320, 180, 48000
with av.open(out, "w") as container:
    video = container.add_stream("libx264", rate=fps)
    video.width, video.height, video.pix_fmt = w, h, "yuv420p"
    video.options = {"preset": "veryfast", "g": str(fps * 2)}
    audio = container.add_stream("aac", rate=rate)
    audio.layout = "stereo"
    xs = np.arange(w, dtype=np.int64)
    samples_per_frame = 1024
    t_audio = 0
    for i in range(seconds * fps):
        img = np.zeros((h, w, 3), dtype=np.uint8)
        img[:, :, 0] = (xs + i * 3) % 256
        img[:, :, 1] = (i * 2) % 256
        img[:, :, 2] = 255 - (xs + i) % 256
        frame = av.VideoFrame.from_ndarray(img, format="rgb24")
        frame.pts = i
        for packet in video.encode(frame):
            container.mux(packet)
        while t_audio < (i + 1) * rate // fps:
            n = samples_per_frame
            t = (np.arange(n) + t_audio) / rate
            tone = (0.2 * np.sin(2 * math.pi * 440 * t)).astype(np.float32)
            af = av.AudioFrame.from_ndarray(np.vstack([tone, tone]), format="fltp", layout="stereo")
            af.sample_rate = rate
            af.pts = t_audio
            for packet in audio.encode(af):
                container.mux(packet)
            t_audio += n
    for packet in video.encode():
        container.mux(packet)
    for packet in audio.encode():
        container.mux(packet)
