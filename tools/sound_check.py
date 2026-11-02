#!/usr/bin/env python3
"""Compare the recorded sound of two phone videos.

    python tools/sound_check.py A.mp4 B.mp4

A = clip recorded while the prompter was listening.
B = clip recorded with the prompter off.

When two apps capture the mic at once, Android may change the audio path.
This script checks that the camera's recorded sound did not change.

Needs ffmpeg and ffprobe on PATH. Standard library only. If numpy is
installed it is used for the tone check, but it is not needed.

Exit code: 0 nothing flagged, 1 something flagged, 2 usage or tool error.
"""
from __future__ import annotations

import array
import cmath
import json
import math
import os
import re
import shutil
import subprocess
import sys
import textwrap
import traceback

try:
    import numpy as _np
except Exception:  # missing or broken: the plain Python path takes over
    _np = None

# ---- the limits from the test plan ----------------------------------------
SILENCE_DB = -90         # near digital silence: a real dropout is a hole of zeros; room noise in a pause stays above this
SILENCE_MIN_S = 0.05     # shortest quiet stretch that counts as a gap
EDGE_S = 0.15            # only the first and last 0.15 s are skipped: a glitch from the listener starting lands early
LOUD_LIMIT_LU = 3.0      # flag if integrated loudness differs by more than this
TONE_RATIO_LIMIT = 2.0   # flag if one high-band share is more than this times the other
TONE_TINY = 0.005        # both high-band shares under 0.5 percent: too small to compare
LOW_HZ = 300.0           # tone bands: below 300 Hz, 300 to 3400 Hz, above 3400 Hz
HIGH_HZ = 3400.0
MAX_FRAMES = 480         # FFT frames per file (the same frames with or without numpy)
RUN_TIMEOUT_S = 900
WRAP = 100

FFMPEG = "ffmpeg"
FFPROBE = "ffprobe"

NUM = r"(-?inf|nan|-?\d+(?:\.\d+)?)"

USAGE = (
    "Usage: python tools/sound_check.py A.mp4 B.mp4\n"
    "  A = clip recorded with the prompter listening\n"
    "  B = clip recorded with the prompter off\n"
    "Needs ffmpeg and ffprobe on PATH.\n"
    "Exit code: 0 nothing flagged, 1 something flagged, 2 usage or tool error."
)


class ToolError(Exception):
    """Something stopped the check from running. This is not a finding about the sound."""


# ---- small helpers ---------------------------------------------------------

def to_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def to_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def finite(value):
    return value is not None and math.isfinite(value)


def tail(text, count=5):
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    return " | ".join(lines[-count:]) if lines else "(no message)"


def run(cmd):
    """Run a program with a list of arguments (never a shell string)."""
    try:
        proc = subprocess.run(
            cmd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=RUN_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        raise ToolError("%s ran longer than %d seconds and was stopped."
                        % (os.path.basename(cmd[0]), RUN_TIMEOUT_S))
    except OSError as exc:
        raise ToolError("Could not start %s: %s" % (cmd[0], exc))
    err = proc.stderr.decode("utf-8", errors="replace")
    return proc.returncode, proc.stdout, err


# ---- ffprobe: the audio stream format -------------------------------------

def probe(path):
    code, out, err = run([FFPROBE, "-v", "error", "-show_streams", "-show_format",
                          "-of", "json", path])
    if code != 0:
        raise ToolError("ffprobe could not open %s. %s" % (path, tail(err)))
    try:
        data = json.loads(out.decode("utf-8", errors="replace"))
    except ValueError:
        raise ToolError("ffprobe gave output I could not read for %s." % path)
    audio = [s for s in data.get("streams", []) if s.get("codec_type") == "audio"]
    if not audio:
        return None
    stream = audio[0]
    fmt = data.get("format") or {}
    return {
        "codec": stream.get("codec_name") or "unknown",
        "profile": stream.get("profile") or "",
        "rate": to_int(stream.get("sample_rate")),
        "channels": to_int(stream.get("channels")),
        "layout": stream.get("channel_layout") or "",
        "bit_rate": to_int(stream.get("bit_rate")),
        "duration": to_float(stream.get("duration")) or to_float(fmt.get("duration")),
        "streams": len(audio),
    }


def chan_text(info):
    n = info["channels"]
    word = "channel" if n == 1 else "channels"
    if info["layout"]:
        return "%d %s (%s)" % (n, word, info["layout"])
    return "%d %s" % (n, word)


def short_format(info):
    return "%s, %d Hz, %s" % (info["codec"], info["rate"], chan_text(info))


def format_line(info):
    codec = info["codec"] + (" (%s)" % info["profile"] if info["profile"] else "")
    kbps = ("%d kbps" % round(info["bit_rate"] / 1000.0)) if info["bit_rate"] else "bit rate unknown"
    length = ("%.2f s" % info["duration"]) if info["duration"] else "length unknown"
    return "%s, %d Hz, %s, %s, %s" % (codec, info["rate"], chan_text(info), kbps, length)


def format_diffs(ai, bi):
    diffs = []
    if ai["rate"] != bi["rate"]:
        diffs.append("Sample rate: A %d Hz, B %d Hz." % (ai["rate"], bi["rate"]))
    if ai["channels"] != bi["channels"]:
        diffs.append("Channels: A %s, B %s." % (chan_text(ai), chan_text(bi)))
    elif ai["layout"] and bi["layout"] and ai["layout"] != bi["layout"]:
        diffs.append("Channel layout: A %s, B %s." % (ai["layout"], bi["layout"]))
    if ai["codec"] != bi["codec"]:
        diffs.append("Codec: A %s, B %s." % (ai["codec"], bi["codec"]))
    return diffs


# ---- ffmpeg: loudness, volume, dropouts -----------------------------------

def ffmpeg_audio_pass(path, audio_filter):
    """Run one audio filter over the first audio stream. Returns ffmpeg's log text."""
    cmd = [FFMPEG, "-hide_banner", "-nostats", "-nostdin", "-v", "info",
           "-i", path, "-map", "0:a:0", "-vn", "-af", audio_filter, "-f", "null", "-"]
    code, _out, err = run(cmd)
    if code != 0:
        raise ToolError("ffmpeg could not read the audio of %s. %s" % (path, tail(err)))
    return err


def measure_loudness(path):
    err = ffmpeg_audio_pass(path, "ebur128=peak=true:framelog=quiet")
    pos = err.rfind("Summary:")
    if pos < 0:
        raise ToolError("ffmpeg gave no loudness summary for %s. %s" % (path, tail(err)))
    summary = err[pos:]
    m_int = re.search(r"\bI:\s*" + NUM + r"\s*LUFS", summary)
    m_peak = re.search(r"True peak:\s*Peak:\s*" + NUM + r"\s*dBFS", summary)
    if not m_int:
        raise ToolError("Could not find the loudness number for %s. %s" % (path, tail(summary)))
    return {
        "integrated": to_float(m_int.group(1)),
        "true_peak": to_float(m_peak.group(1)) if m_peak else None,
    }


def measure_volume(path):
    err = ffmpeg_audio_pass(path, "volumedetect")
    m_mean = re.search(r"mean_volume:\s*" + NUM + r"\s*dB", err)
    m_max = re.search(r"max_volume:\s*" + NUM + r"\s*dB", err)
    if not m_mean or not m_max:
        raise ToolError("Could not find mean and max volume for %s. %s" % (path, tail(err)))
    return {"mean": to_float(m_mean.group(1)), "max": to_float(m_max.group(1))}


def find_dropouts(path, duration):
    """Quiet stretches inside the clip, ignoring the first and last EDGE_S seconds."""
    lo, hi = EDGE_S, duration - EDGE_S
    if hi - lo < SILENCE_MIN_S:
        return {"window": None, "gaps": []}
    err = ffmpeg_audio_pass(
        path, "silencedetect=noise=%ddB:duration=%g" % (SILENCE_DB, SILENCE_MIN_S))
    spans = []
    open_start = None
    for line in err.splitlines():
        if "silencedetect" not in line:
            continue
        m = re.search(r"silence_start:\s*(\S+)", line)
        if m:
            open_start = to_float(m.group(1))
            continue
        m = re.search(r"silence_end:\s*(\S+)", line)
        if m:
            end = to_float(m.group(1))
            m_len = re.search(r"silence_duration:\s*(\S+)", line)
            length = to_float(m_len.group(1)) if m_len else None
            if open_start is not None:
                start = open_start
            elif end is not None and length is not None:
                start = end - length
            else:
                start = None
            if start is not None and end is not None:
                spans.append((start, end))
            open_start = None
    if open_start is not None:  # the quiet stretch ran to the end of the clip
        spans.append((open_start, duration))
    gaps = []
    for start, end in spans:
        s, e = max(start, lo), min(end, hi)
        if e - s >= SILENCE_MIN_S - 1e-6:
            gaps.append((s, e - s))
    return {"window": (lo, hi), "gaps": gaps}


# ---- tone: share of energy in three bands ---------------------------------

def decode_mono(path, rate):
    """Decode the first audio stream to mono 32-bit float PCM at the file's own rate."""
    cmd = [FFMPEG, "-hide_banner", "-nostdin", "-v", "error", "-i", path,
           "-map", "0:a:0", "-vn", "-ac", "1", "-ar", str(rate),
           "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1"]
    code, out, err = run(cmd)
    if code != 0 or not out:
        raise ToolError("ffmpeg could not decode the audio of %s. %s" % (path, tail(err)))
    return out


def fft_size(rate):
    n = 512
    while n * 25 < rate:  # bins about 25 Hz wide or finer
        n *= 2
    return n


def frame_starts(total, n):
    """Start positions of the frames: half overlapping, spread evenly if there are many."""
    hop = n // 2
    count = (total - n) // hop + 1
    if count <= MAX_FRAMES:
        return [i * hop for i in range(count)]
    return [int(round(j * (count - 1) / (MAX_FRAMES - 1))) * hop for j in range(MAX_FRAMES)]


def make_twiddles(n):
    table = {}
    size = n
    while size >= 4:
        table[size] = [cmath.exp(-2j * math.pi * k / size) for k in range(size // 2)]
        size //= 2
    return table


def fft(x, table):
    """Plain Python FFT on a list of complex numbers. The length is a power of two."""
    n = len(x)
    if n == 4:
        a, b, c, d = x
        s0, s1 = a + c, a - c
        t0, t1 = b + d, (b - d) * -1j
        return [s0 + t0, s1 + t1, s0 - t0, s1 - t1]
    if n <= 2:
        return x if n == 1 else [x[0] + x[1], x[0] - x[1]]
    even = fft(x[0::2], table)
    odd = fft(x[1::2], table)
    t = [w * o for w, o in zip(table[n], odd)]
    return [e + u for e, u in zip(even, t)] + [e - u for e, u in zip(even, t)]


def power_plain(x, n, starts):
    """Summed power per bin (bins 1 to n/2-1), two real frames per complex FFT."""
    win = [0.5 - 0.5 * math.cos(2.0 * math.pi * i / n) for i in range(n)]
    table = make_twiddles(n)
    half = n // 2
    acc = [0.0] * (half - 1)
    zeros = [0.0] * n
    for j in range(0, len(starts), 2):
        a = x[starts[j]:starts[j] + n]
        b = x[starts[j + 1]:starts[j + 1] + n] if j + 1 < len(starts) else zeros
        spec = fft([complex(p * w, q * w) for p, q, w in zip(a, b, win)], table)
        pos = spec[1:half]
        neg = [v.conjugate() for v in spec[n - 1:half:-1]]
        both = [u + v for u, v in zip(pos, neg)]
        diff = [u - v for u, v in zip(pos, neg)]
        acc = [t + (p.real * p.real + p.imag * p.imag + q.real * q.real + q.imag * q.imag) * 0.25
               for t, p, q in zip(acc, both, diff)]
    return acc


def power_numpy(x, n, starts):
    win = 0.5 - 0.5 * _np.cos(2.0 * _np.pi * _np.arange(n) / n)
    index = _np.asarray(starts)[:, None] + _np.arange(n)[None, :]
    spec = _np.fft.rfft(x[index] * win, axis=1)
    power = (spec.real ** 2 + spec.imag ** 2).sum(axis=0)
    return power[1:n // 2].tolist()


def tone_analysis(raw, rate):
    count = len(raw) // 4
    if count == 0:
        return None
    raw = raw[:count * 4]
    n = fft_size(rate)
    if _np is not None:
        x = _np.frombuffer(raw, dtype="<f4").astype(_np.float64)
        if len(x) < n:
            x = _np.concatenate([x, _np.zeros(n - len(x))])
        starts = frame_starts(len(x), n)
        power = power_numpy(x, n, starts)
    else:
        x = array.array("f")
        if x.itemsize != 4:
            raise ToolError("This Python build has an unusual float size. Install numpy and retry.")
        x.frombytes(raw)
        if sys.byteorder == "big":
            x.byteswap()
        if len(x) < n:
            x.extend([0.0] * (n - len(x)))
        starts = frame_starts(len(x), n)
        power = power_plain(x, n, starts)
    return {"rate": rate, "n": n, "power": power, "frames": len(starts), "samples": count}


def band_shares(tone, fmax=None):
    """(low, middle, high) shares of the energy, or None if there is no sound.

    The DC bin and the top bin are left out. If fmax is given, nothing above it counts.
    """
    if tone is None:
        return None
    rate, n = tone["rate"], tone["n"]
    low = mid = high = 0.0
    for i, p in enumerate(tone["power"]):
        f = (i + 1) * rate / float(n)
        if fmax is not None and f > fmax:
            break
        if f < LOW_HZ:
            low += p
        elif f < HIGH_HZ:
            mid += p
        else:
            high += p
    total = low + mid + high
    if not total > 0.0:
        return None
    return (low / total, mid / total, high / total)


# ---- one file --------------------------------------------------------------

def analyze(path):
    info = probe(path)
    if info is None:
        return {"path": path, "info": None}
    if not info["rate"] or not info["channels"]:
        raise ToolError("ffprobe did not report a sample rate and channel count for %s." % path)
    loud = measure_loudness(path)
    volume = measure_volume(path)
    raw = decode_mono(path, info["rate"])
    tone = tone_analysis(raw, info["rate"])
    if not info["duration"]:
        info["duration"] = (len(raw) // 4) / float(info["rate"])
    drops = find_dropouts(path, info["duration"])
    return {"path": path, "info": info, "loud": loud, "volume": volume,
            "tone": tone, "shares": band_shares(tone), "drops": drops}


# ---- printing --------------------------------------------------------------

def db(value, unit):
    if value is None:
        return "unknown"
    return "%.1f %s" % (value, unit)


def show_file(label, res):
    print("=== %s ===" % label)
    print("File:      %s" % res["path"])
    info = res["info"]
    if info is None:
        print("No audio track in this file.")
        print()
        return
    print("Format:    %s" % format_line(info))
    if info["streams"] > 1:
        print("           This file has %d audio tracks. Only the first one is checked."
              % info["streams"])
    loud, vol = res["loud"], res["volume"]
    print("Loudness:  integrated %s, true peak %s"
          % (db(loud["integrated"], "LUFS"), db(loud["true_peak"], "dBFS")))
    print("           mean volume %s, max volume %s"
          % (db(vol["mean"], "dB"), db(vol["max"], "dB")))
    drops = res["drops"]
    if drops["window"] is None:
        print("Dropouts:  not checked, the clip is too short (needs more than %.2f s)"
              % (2 * EDGE_S + SILENCE_MIN_S))
    else:
        how = "checked %.2f s to %.2f s, below %d dB, %g s or longer" % (
            drops["window"][0], drops["window"][1], SILENCE_DB, SILENCE_MIN_S)
        if not drops["gaps"]:
            print("Dropouts:  none (%s)" % how)
        else:
            print("Dropouts:  %d found (%s)" % (len(drops["gaps"]), how))
            for start, length in drops["gaps"][:10]:
                print("             at %.2f s, lasting %.2f s" % (start, length))
            if len(drops["gaps"]) > 10:
                print("             and %d more" % (len(drops["gaps"]) - 10))
    shares = res["shares"]
    if shares is None:
        print("Tone:      no sound to measure")
    else:
        print("Tone:      below 300 Hz %.1f%%, 300 Hz to 3.4 kHz %.1f%%, above 3.4 kHz %.1f%% (up to %g kHz)"
              % (shares[0] * 100, shares[1] * 100, shares[2] * 100, info["rate"] / 2000.0))
    print()


def say(tag, text):
    prefix = "%-5s " % tag
    print(textwrap.fill(text, width=WRAP, initial_indent=prefix,
                        subsequent_indent=" " * len(prefix)))


# ---- the verdict -----------------------------------------------------------

def verdict(a, b):
    """Return a list of (tag, text). Tag is FLAG, OK, NOTE or --."""
    out = []
    ai, bi = a["info"], b["info"]

    # 1. Format. The most important check, so it comes first.
    if ai is None or bi is None:
        missing = " and ".join(name for name, res in (("A", a), ("B", b)) if res["info"] is None)
        out.append(("FLAG", "FORMAT CHANGED. %s has no audio track at all." % missing))
        for name in ("Dropouts", "Loudness", "Tone"):
            out.append(("--", "%s: not compared, because a clip has no audio." % name))
        return out

    diffs = format_diffs(ai, bi)
    if diffs:
        out.append(("FLAG", "FORMAT CHANGED. %s The camera's audio path is different in the two "
                            "clips. This is the most important red flag." % " ".join(diffs)))
    else:
        out.append(("OK", "SAME FORMAT. Both clips are %s." % short_format(ai)))
    ba, bb = ai["bit_rate"], bi["bit_rate"]
    if not diffs and ba and bb and abs(ba - bb) / float(max(ba, bb)) > 0.25:
        out.append(("NOTE", "Bit rate differs: A %d kbps, B %d kbps. This alone is not flagged. "
                            "Small differences are normal for AAC, but a big one can mean the "
                            "encoder was set differently." % (round(ba / 1000.0), round(bb / 1000.0))))

    # 2. Dropouts. Two takes cannot be matched second by second, so we compare counts.
    da, db_ = a["drops"], b["drops"]
    if da["window"] is None or db_["window"] is None:
        out.append(("--", "Dropouts: not checked, a clip is too short."))
    else:
        na, nb = len(da["gaps"]), len(db_["gaps"])
        if na > nb:
            spots = "; ".join("at %.2f s for %.2f s" % g for g in da["gaps"][:5])
            out.append(("FLAG", "Dropouts: A has %d quiet gap%s inside the clip, B has %d (%s). "
                                "The capture may have hiccuped when the listener started. "
                                "Listen to A at those spots."
                                % (na, "" if na == 1 else "s", nb, spots)))
        elif na == 0 and nb == 0:
            out.append(("OK", "Dropouts: none in A, none in B."))
        else:
            out.append(("OK", "Dropouts: A has %d, B has %d. A has no extra gaps, so nothing is "
                              "flagged. Gaps like this can just be pauses in the speech." % (na, nb)))

    # 3. Loudness. Two takes are different readings, so small gaps are normal.
    la, lb = a["loud"]["integrated"], b["loud"]["integrated"]
    if not (finite(la) and finite(lb)):
        out.append(("FLAG", "Loudness: could not measure one of the clips (A %s, B %s). "
                            "It may be silent." % (db(la, "LUFS"), db(lb, "LUFS"))))
    else:
        gap = abs(la - lb)
        text = ("Loudness: A %.1f LUFS, B %.1f LUFS, %.1f LU apart (limit %g LU). "
                % (la, lb, gap, LOUD_LIMIT_LU))
        if gap > LOUD_LIMIT_LU:
            text += ("A is %.1f LU %s than B. That is more than two takes should differ."
                     % (gap, "louder" if la > lb else "quieter"))
        else:
            text += "These are two different takes, so small differences are normal."
        if ai["channels"] != bi["channels"]:
            text += " The channel counts differ, so loudness can shift for that reason alone."
        out.append(("FLAG" if gap > LOUD_LIMIT_LU else "OK", text))

    # 4. Tone. Compare the share above 3.4 kHz. Different sample rates: compare to the lower top.
    fmax, why = None, ""
    if ai["rate"] != bi["rate"]:
        fmax = min(ai["rate"], bi["rate"]) / 2.0
        why = " The sample rates differ, so only sound up to %g kHz is compared." % (fmax / 1000.0)
    sa, sb = band_shares(a["tone"], fmax), band_shares(b["tone"], fmax)
    if sa is None or sb is None:
        out.append(("--", "Tone: not compared, a clip has no sound to measure."))
    else:
        ha, hb = sa[2], sb[2]
        big, small = max(ha, hb), min(ha, hb)
        base = ("Tone: the share of sound above 3.4 kHz is %.1f%% in A and %.1f%% in B."
                % (ha * 100, hb * 100))
        if big < TONE_TINY:
            out.append(("OK", base + " Both are tiny, so there is nothing to compare." + why))
        else:
            ratio = big / small if small > 0 else float("inf")
            if ratio > TONE_RATIO_LIMIT:
                size = "far more than twice" if ratio == float("inf") else "%.1f times" % ratio
                out.append(("FLAG", base + " The larger one is %s the smaller one (limit %g times). "
                                          "The sound may be processed differently, for example "
                                          "with voice-call style filtering."
                            % (size, TONE_RATIO_LIMIT) + why))
            else:
                out.append(("OK", base + " Neither is more than twice the other." + why))
    return out


# ---- main ------------------------------------------------------------------

def find_tools():
    global FFMPEG, FFPROBE
    found_ffmpeg, found_ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    missing = [name for name, p in (("ffmpeg", found_ffmpeg), ("ffprobe", found_ffprobe)) if not p]
    if missing:
        raise ToolError("%s not found on PATH. This script needs both ffmpeg and ffprobe. "
                        "Install ffmpeg (for example: winget install Gyan.FFmpeg), open a new "
                        "terminal, and run this again." % " and ".join(missing))
    FFMPEG, FFPROBE = found_ffmpeg, found_ffprobe


def check_file(path):
    full = os.path.abspath(path)
    if not os.path.isfile(full):
        raise ToolError("File not found: %s" % path)
    return full


def main(argv):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except Exception:
            pass
    args = argv[1:]
    if args and args[0] in ("-h", "--help", "/?"):
        print(USAGE)
        return 0
    if len(args) != 2:
        print("Give exactly two video files.\n" + USAGE, file=sys.stderr)
        return 2
    try:
        find_tools()
        path_a, path_b = check_file(args[0]), check_file(args[1])
        if os.path.samefile(path_a, path_b):
            raise ToolError("A and B are the same file. Give two different clips.")
        res_a, res_b = analyze(path_a), analyze(path_b)
        if res_a["info"] is None and res_b["info"] is None:
            raise ToolError("Neither file has an audio track, so there is nothing to compare.")
    except ToolError as exc:
        print("Tool error: %s" % exc, file=sys.stderr)
        return 2

    print("SOUND CHECK (A = prompter on, B = prompter off)")
    print()
    show_file("A (prompter ON)", res_a)
    show_file("B (prompter OFF)", res_b)

    print("=== VERDICT ===")
    lines = verdict(res_a, res_b)
    for tag, text in lines:
        say(tag, text)
    flagged = sum(1 for tag, _ in lines if tag == "FLAG")
    print()
    if flagged:
        print("RESULT: %d item%s flagged. See the FLAG lines above."
              % (flagged, "" if flagged == 1 else "s"))
        return 1
    print("RESULT: nothing flagged.")
    return 0


if __name__ == "__main__":
    try:
        code = main(sys.argv)
    except KeyboardInterrupt:
        print("Stopped.", file=sys.stderr)
        code = 2
    except Exception as exc:  # a bug must never look like "something flagged" (exit 1)
        traceback.print_exc()
        print("Unexpected error: %s: %s" % (type(exc).__name__, exc), file=sys.stderr)
        code = 2
    sys.exit(code)
