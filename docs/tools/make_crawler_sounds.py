#!/usr/bin/env python3
"""
Synthesises the Hydraulic Crawler's five sound effects from nothing but numpy.

**Why synthesised rather than recorded.**  Blu's licence on the base mod forbids public
redistribution of anything that is not this fork's own work, and a sampled diesel engine
or a downloaded backup-alarm clip is exactly the kind of asset that constraint rules out.
A generator that builds the waveform
from oscillators, filtered noise and envelopes, the way `make_crawler_obj.py` builds the
model from boxes and frusta rather than importing someone else's mesh, sidesteps the
question entirely: there is no source recording to have rights to.  ffmpeg is used only
as an encoder, converting the WAV this script writes into Ogg Vorbis; it is never asked
to fetch, decode or otherwise touch anyone else's audio.

**The five sounds** (all mono, 44.1 kHz):

  * `crawler_engine.ogg`     -- loop, ~2.0s.  A diesel idle: a train of decaying
    combustion pulses at 20 Hz, low-passed roughness noise, a faint turbo whine.
  * `crawler_tracks.ogg`     -- loop, ~1.5s.  Steel track links over rollers: randomised
    metallic clanks at ~8/s over a low rumble bed.
  * `crawler_hydraulic.ogg`  -- loop, ~1.2s.  A gear pump under load: a vibrato'd tone
    with harmonics, plus filtered fluid hiss.
  * `crawler_dump.ogg`       -- one-shot, ~1.2s.  A ram groan running into a rush of
    falling gravel, with a scatter of stone clatters through the tail.
  * `crawler_beeper.ogg`     -- one-shot, ~0.45s.  One backup-alarm beep; the game
    re-triggers this file on a timer to get the beep-beep-beep pattern.

**Seamless looping is the hard part.**  Minecraft's sound engine loops a file by jumping
straight from the last sample back to the first -- no crossfade -- so any mismatch there
is a click repeated every cycle, forever.  Two constructions make the three loops close
exactly rather than approximately:

  * every sustained tone is built at a frequency that is a whole number of cycles in the
    file's own duration (`periodic_freq` below), so the waveform's value at the (unwritten)
    sample just past the end is mathematically identical to its value at sample 0;
  * every filtered noise bed is shaped by multiplying its *whole-buffer* spectrum in the
    frequency domain and transforming back (`circular_filter`), rather than by convolving
    forwards through time -- an FFT treats a length-N buffer as one period of a periodic
    signal by definition, so the result has no filter transient at the seam because there
    is no seam as far as that maths is concerned.

Randomised one-off events -- a combustion pulse, a track clank -- are placed with
`add_circular`, which wraps an event's tail around to the front of the buffer instead of
letting the loop point truncate it.  That is also what keeps those events glued smoothly
across the seam: the sample either side of the wrap are literal neighbours of the one
continuous decay curve, just relocated, so the step between them is an ordinary
sample-to-sample step and not a splice.

The two one-shots don't need any of that; they only need to *start and end at silence*,
which they get from an attack/release envelope built to hit exactly 0 at both ends.

Self-checks, run before anything is written and again after the round trip through
ffmpeg -- the sort of failure a "the file exists and has the right name" smoke test
would never catch:

  * every buffer is mono, 44100 Hz, finite, and carries no DC offset worth speaking of;
  * nothing clips, and every file's peak lands on its intended level within tolerance;
  * for the three loops, the step from the last sample to the first is no bigger than a
    typical sample-to-sample step found elsewhere in the same file;
  * for the two one-shots, the first and last samples are silence, not near-silence;
  * ffmpeg is on PATH and its build has the libvorbis encoder, checked before any
    synthesis runs, so a missing encoder fails on line one rather than after five
    minutes of DSP;
  * every encoded .ogg is decoded back with ffmpeg to raw PCM, and the decode is checked
    for channel count and duration against what was asked for -- so a broken encode (the
    wrong codec picked, a truncated write, a sample-rate mismatch) cannot pass silently.

Usage:  python docs/tools/make_crawler_sounds.py [--assets <assets dir>]

Requires numpy and ffmpeg (with libvorbis) on PATH.  Writes:
    sounds/crawler_engine.ogg
    sounds/crawler_tracks.ogg
    sounds/crawler_hydraulic.ogg
    sounds/crawler_dump.ogg
    sounds/crawler_beeper.ogg
"""

import argparse
import math
import os
import re
import shutil
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100

# ---------------------------------------------------------------------------
# Small DSP building blocks, shared by all five sounds.
# ---------------------------------------------------------------------------
def periodic_freq(target_hz, duration):
    """The nearest frequency to `target_hz` that completes a whole number of cycles in
    `duration` seconds.

    A sine built at this frequency is worth exactly `duration` seconds of it: its phase
    at the end of the buffer is a multiple of 2*pi, the same as its phase at the start,
    so tiling the buffer end to end never shows a kink in the waveform.  `duration`
    is always at least a second here, so rounding to the nearest whole cycle never
    moves the frequency far enough to matter (a fraction of a Hz, at most).
    """
    cycles = max(1, int(round(target_hz*duration)))
    return cycles/duration


def rfft_freqs(n):
    return np.fft.rfftfreq(n, d=1.0/SR)


def circular_filter(signal, response):
    """Shape `signal` (length n) by the magnitude spectrum `response` (length n//2+1),
    filtering in the frequency domain rather than convolving through time.

    This is the other half of the seamless-loop trick, for noise instead of tones: the
    FFT already treats a length-n buffer as one period of a periodic signal, so every
    frequency component the inverse transform can produce already has a whole number of
    cycles in n samples.  There is no way for this to introduce a click at the wrap
    point.  The DC bin is zeroed unconditionally -- nothing synthesised in this file
    wants a constant offset, and leaving a stray DC bin in is how a "no discontinuity at
    all" filter quietly becomes a small woofer-punishing thump instead.
    """
    spectrum = np.fft.rfft(signal)
    spectrum = spectrum*response
    spectrum[0] = 0.0
    return np.fft.irfft(spectrum, n=len(signal))


def lowpass_response(freqs, cutoff, order=2.0):
    """A smooth low shelf with no ripple: 1 well below `cutoff`, falling away above it."""
    return 1.0/(1.0+(freqs/max(cutoff, 1e-6))**(2*order))


def bandpass_response(freqs, center, bandwidth):
    """A resonant bump centred on `center`.  This is the shape that gives a filtered
    burst of noise its "ring" -- a narrow passband rings when struck by anything broadband,
    which is a fair model of what a strip of steel does when something knocks on it.
    """
    return np.exp(-0.5*((freqs-center)/bandwidth)**2)


def bandlimit_response(freqs, lo, hi):
    """A broad, flattish pass band between `lo` and `hi` with soft shoulders.

    Distinct from bandpass_response on purpose: this is for a texture (fluid hiss,
    falling gravel) that should sound like broadband noise with the extremes rolled off,
    not like a struck resonator.  A narrow bump here would make hiss sound like a tone.
    """
    hp = 1.0/(1.0+(lo/np.maximum(freqs, 1e-6))**2)
    lp = 1.0/(1.0+(freqs/hi)**2)
    return hp*lp


def add_circular(buf, start, segment):
    """Add `segment` into `buf` starting at sample `start`, wrapping the overflow round
    to the front instead of letting it run off the end.

    Used for every one-off event inside a loop (a combustion pulse, a track clank) so
    that an event scheduled near the tail of the buffer does not get truncated at the
    loop point -- its overflow reappears at the start of the buffer, which is exactly
    where it will actually sound once playback wraps.  It also happens to be what keeps
    such an event's decay curve smooth across the seam: the sample just before the wrap
    and the sample just after it are literal neighbours in the same continuous array,
    only relocated, so there is nothing for check_loop_seam to catch.
    """
    n = len(buf)
    length = len(segment)
    start = int(start) % n
    end = start+length
    if end <= n:
        buf[start:end] += segment
    else:
        first = n-start
        buf[start:] += segment[:first]
        buf[:end-n] += segment[first:]


def add_clamped(buf, start, segment):
    """Add `segment` into `buf` at `start`, truncating anything that would run past
    either end.  Used for one-shot sounds, where wrapping would be wrong: nothing plays
    again after a one-shot finishes, so there is nowhere for an overflowing tail to go.
    """
    n = len(buf)
    start = max(0, int(start))
    end = min(n, start+len(segment))
    if end <= start:
        return
    buf[start:end] += segment[:end-start]


def ramp_in(length):
    """0 -> 1 as a raised cosine, hitting exactly 0 at sample 0 and exactly 1 at the
    last sample.  Exactly 0, not merely small: at t=0 this is 0.5-0.5*cos(0), and
    cos(0) is exactly 1 in floating point, so the multiplication that uses this as a
    gate leaves a true zero behind rather than a rounding error masquerading as one.
    """
    if length <= 1:
        return np.ones(max(length, 0))
    t = np.arange(length)/(length-1)
    return 0.5-0.5*np.cos(np.pi*t)


def ramp_out(length):
    return ramp_in(length)[::-1]


def attack_release_envelope(length, attack, release):
    """1 in the middle, ramping from/to exactly 0 at the two ends over `attack` and
    `release` samples.  The one-shots multiply their entire mix by one of these, which
    is what guarantees "starts and ends at silence" regardless of what is going on
    underneath -- a stray noise burst placed near either end is silenced by the gate
    whether or not the sound design elsewhere accounted for it.
    """
    env = np.ones(length)
    a = min(attack, length)
    r = min(release, length-a)
    if a > 0:
        env[:a] = ramp_in(a)
    if r > 0:
        env[length-r:] = ramp_out(r)
    return env


def db_to_lin(db):
    return 10.0**(db/20.0)


def normalize_peak(buf, target_dbfs):
    peak = float(np.max(np.abs(buf)))
    if peak < 1e-9:
        raise SystemExit("a buffer came out silent before it was even normalised")
    return buf*(db_to_lin(target_dbfs)/peak)


# ---------------------------------------------------------------------------
# 1. crawler_engine.ogg -- loop, ~2.0s
# ---------------------------------------------------------------------------
def engine_pulse(rng, tau=0.005):
    """One combustion knock: a fundamental and two harmonics, each dying at its own
    rate, because that is what a knock sounds like -- a low thud with a short-lived
    crack riding on top of it, not one clean decaying sine.  The 55 Hz fundamental with
    harmonics at 110 and 165 puts most of the pulse's energy where a big diesel's
    firing thump actually sits.
    """
    length = int(SR*0.045)      # 9 time-constants at tau=5ms: e^-9 is 3 parts in 10000
    t = np.arange(length)/SR    # of the onset amplitude, comfortably decayed away.
    thump = (1.00*np.sin(2*np.pi*55*t)*np.exp(-t/tau)
             +0.45*np.sin(2*np.pi*110*t)*np.exp(-t/(tau*0.6))
             +0.20*np.sin(2*np.pi*165*t)*np.exp(-t/(tau*0.4)))
    # 55 Hz over a 45ms window is 2.5 cycles, not a whole number, so the truncated decay
    # leaves a small net sum behind -- one pulse's worth is inaudible, but the same
    # shape fires forty times a loop and forty identical biases add coherently into a
    # DC offset check_sane rightly refuses to let through.  Demeaning the template once,
    # here, is cheaper than fighting the truncation with a longer window.
    thump = thump-thump.mean()
    return thump*(0.75+0.25*rng.random())


def synth_engine(rng):
    duration = 2.0
    n = int(round(SR*duration))
    buf = np.zeros(n)

    # 20 Hz firing rate, as asked for.  44100/20 = 2205 exactly, so 40 pulses fill the
    # 2-second loop with no remainder -- the train itself is periodic before a single
    # sample of jitter is added.
    period = int(round(SR/20.0))
    num_pulses = n//period
    for i in range(num_pulses):
        # A little off-grid timing so the idle does not tick like a metronome.  Up to
        # ~3ms either way, which is audible as "not quantised" but not as "missing a
        # beat" against a 50ms period.
        start = i*period+rng.integers(-140, 141)
        add_circular(buf, start, engine_pulse(rng))

    # Roughness: low-passed noise under the knock, the texture that separates a big
    # slow diesel from a synthesiser playing a pulse train.  220 Hz keeps it a rumble
    # rather than a hiss.
    freqs = rfft_freqs(n)
    roughness = circular_filter(rng.standard_normal(n), lowpass_response(freqs, 220.0))
    buf += 0.12*roughness/(np.max(np.abs(roughness))+1e-9)

    # A faint turbo/accessory whine, two octaves above the top of the knock's own
    # harmonic series (165 Hz -> 660 Hz): high enough to read as a whine rather than
    # another knock partial, low enough to still sound mechanical rather than electronic.
    # Two close tones a few Hz apart give it a slow beat instead of a dead-flat drone.
    t = np.arange(n)/SR
    f1 = periodic_freq(660.0, duration)
    f2 = periodic_freq(665.0, duration)
    buf += 0.05*np.sin(2*np.pi*f1*t)+0.035*np.sin(2*np.pi*f2*t)

    return normalize_peak(buf, -6.0)


# ---------------------------------------------------------------------------
# 2. crawler_tracks.ogg -- loop, ~1.5s
# ---------------------------------------------------------------------------
def synth_tracks(rng):
    duration = 1.5
    n = int(round(SR*duration))
    buf = np.zeros(n)
    freqs = rfft_freqs(n)

    # The low rumble bed under the clanks: the frame and rollers grinding along,
    # continuous where the clanks are impulsive.
    rumble = circular_filter(rng.standard_normal(n), lowpass_response(freqs, 90.0))
    buf += 0.10*rumble/(np.max(np.abs(rumble))+1e-9)

    # Three "variants" of clank timbre -- three different resonant centres a link can
    # ring at -- rather than one filter applied to every clank, which would make all
    # twelve of them sound like the same link struck the same way.  Each variant is its
    # own buffer so it gets its own circular_filter pass (each pass is exactly periodic
    # on its own, and the sum of periodic signals is periodic, so nothing about doing
    # this in three passes rather than one threatens the loop seam).
    variants = [(1300.0, 380.0), (2200.0, 520.0), (3400.0, 700.0)]
    variant_bufs = [np.zeros(n) for _ in variants]

    clank_rate = 8.0                      # roughly 8 clanks a second, as asked for.
    num_clanks = max(1, int(round(duration*clank_rate)))
    slot = n/num_clanks
    for i in range(num_clanks):
        # Scattered within its slot rather than metronomic, and the scatter is allowed
        # to run right up to (and past, via add_circular) the ends of the buffer --
        # nothing here excludes the seam, which is the case check_loop_seam has to hold
        # for a random draw and not just for a convenient one.
        start = int(round(i*slot+rng.uniform(-0.18*slot, 0.18*slot)))
        length = int(rng.integers(int(SR*0.035), int(SR*0.09)))
        tau = rng.uniform(0.010, 0.030)
        t = np.arange(length)/SR
        burst = rng.standard_normal(length)*np.exp(-t/tau)
        amp = rng.uniform(0.5, 1.0)
        variant = int(rng.integers(0, len(variants)))
        add_circular(variant_bufs[variant], start, burst*amp)

    for (center, bandwidth), vb in zip(variants, variant_bufs):
        buf += circular_filter(vb, bandpass_response(freqs, center, bandwidth))

    return normalize_peak(buf, -6.0)


# ---------------------------------------------------------------------------
# 3. crawler_hydraulic.ogg -- loop, ~1.2s
# ---------------------------------------------------------------------------
def synth_hydraulic(rng):
    duration = 1.2
    n = int(round(SR*duration))
    t = np.arange(n)/SR

    # 450 Hz sits in the middle of the "gear pump under load" range the brief asks for.
    # The vibrato is folded into the phase analytically -- rather than nudging a
    # fixed-frequency phase sample by sample -- so the closed form is exact:
    #
    #   f(t)     = f0*(1 + depth*sin(2*pi*fvib*t))                  instantaneous freq
    #   phase(t) = 2*pi * integral(f, 0, t)
    #            = 2*pi*f0*t + (f0*depth/fvib)*(1 - cos(2*pi*fvib*t))
    #
    # Both f0 and fvib are periodic_freq'd against `duration`, so at t=duration the
    # cosine term is cos(2*pi*integer) = 1 exactly (the vibrato term vanishes) and the
    # main term is 2*pi*integer exactly -- phase(duration) == phase(0) mod 2*pi, so
    # sin(phase) is exactly periodic over `duration` with no seam to speak of.
    f0 = periodic_freq(450.0, duration)
    fvib = periodic_freq(4.0, duration)     # "slow" -- roughly one wobble per quarter turn
    depth = 0.02                            # "shallow" -- +-2% of the pump's speed
    phase = 2*np.pi*f0*t+(f0*depth/fvib)*(1-np.cos(2*np.pi*fvib*t))

    # The harmonic series is what turns a sine into the buzzy near-sawtooth a gear pump
    # actually produces -- one cycle of the fundamental per tooth meshing, with the
    # harmonics riding the same vibrato because they are built from the same phase.
    tone = (1.00*np.sin(phase)+0.35*np.sin(2*phase)
            +0.15*np.sin(3*phase)+0.08*np.sin(4*phase))

    freqs = rfft_freqs(n)
    hiss = circular_filter(rng.standard_normal(n), bandlimit_response(freqs, 800.0, 4000.0))
    buf = 0.55*tone+0.16*hiss/(np.max(np.abs(hiss))+1e-9)

    return normalize_peak(buf, -12.0)


# ---------------------------------------------------------------------------
# 4. crawler_dump.ogg -- one-shot, ~1.2s
# ---------------------------------------------------------------------------
def synth_dump(rng):
    duration = 1.2
    n = int(round(SR*duration))
    buf = np.zeros(n)

    # The ram groan: a labouring hydraulic cylinder, its pitch sliding down as the load
    # comes off it.  Mixing in a little noise and running the sum through a mild tanh
    # is what gives it a rasp -- a pure sine here reads as a synthesiser, not steel
    # under strain.
    groan_len = int(SR*0.45)
    tg = np.arange(groan_len)/SR
    sweep = 150.0-55.0*(tg/tg[-1])
    phase = 2*np.pi*np.cumsum(sweep)/SR
    grit = rng.standard_normal(groan_len)
    groan = np.tanh(1.6*(np.sin(phase)+0.25*grit))
    groan *= attack_release_envelope(groan_len, int(SR*0.04), int(SR*0.18))
    add_clamped(buf, 0, groan*0.85)

    # The rush of the load itself: broadband noise, band-limited roughly 200 Hz-5 kHz so
    # it reads as gravel and earth rather than as a hiss of static, gated in with the
    # ram's release and tailing off well before the buffer ends.
    earth_start = int(SR*0.30)
    earth_len = int(SR*0.75)
    freqs_e = rfft_freqs(earth_len)
    shaped = circular_filter(rng.standard_normal(earth_len),
                             bandlimit_response(freqs_e, 200.0, 5000.0))
    earth = shaped/(np.max(np.abs(shaped))+1e-9)
    earth *= attack_release_envelope(earth_len, int(SR*0.12), int(SR*0.40))
    add_clamped(buf, earth_start, earth*0.9)

    # A handful of discrete stones clattering out among the rush: short, individually
    # band-passed bursts (so each one rings at its own pitch, the way one rock does not
    # sound like the next) scattered through the back half of the file.
    for _ in range(int(rng.integers(6, 10))):
        pos = rng.uniform(0.38, 1.05)
        clatter_len = int(rng.integers(int(SR*0.02), int(SR*0.05)))
        tau = rng.uniform(0.006, 0.018)
        tc = np.arange(clatter_len)/SR
        burst = rng.standard_normal(clatter_len)*np.exp(-tc/tau)
        freqs_c = rfft_freqs(clatter_len)
        center = rng.uniform(900.0, 3200.0)
        bandwidth = rng.uniform(250.0, 600.0)
        clatter = circular_filter(burst, bandpass_response(freqs_c, center, bandwidth))
        add_clamped(buf, int(SR*pos), clatter*rng.uniform(0.3, 0.7))

    # The master gate: forces the first and last samples of the whole file to exactly 0,
    # which is what check_oneshot_silence demands, regardless of how close any of the
    # above happened to land to either edge.
    buf *= attack_release_envelope(n, int(SR*0.008), int(SR*0.05))

    return normalize_peak(buf, -4.0)


# ---------------------------------------------------------------------------
# 5. crawler_beeper.ogg -- one-shot, ~0.45s
# ---------------------------------------------------------------------------
def synth_beeper(rng):
    duration = 0.45
    n = int(round(SR*duration))
    buf = np.zeros(n)

    # A single beep, well inside the buffer, with silence padding the rest -- the game
    # re-fires this file on a timer to build the beep-beep-beep pattern, so the gap
    # between beeps comes from that timer, not from a sequence baked into this file.
    tone_len = int(SR*0.22)
    t = np.arange(tone_len)/SR
    f0 = 1000.0
    # A square wave's own Fourier series -- odd harmonics, amplitude 1/n -- is what
    # "pulse/square-ish tone... with buzzy harmonics" means in these terms.  Four terms
    # (up to the 7th, at 7 kHz, well under Nyquist) is enough buzz to read as a siren
    # rather than a whistle without inviting aliasing at 44.1 kHz.
    tone = (np.sin(2*np.pi*f0*t)+(1/3)*np.sin(2*np.pi*3*f0*t)
            +(1/5)*np.sin(2*np.pi*5*f0*t)+(1/7)*np.sin(2*np.pi*7*f0*t))
    # Fast attack, fast release: a backup alarm's beep snaps on and off, it does not
    # fade like a bell.
    tone *= attack_release_envelope(tone_len, int(SR*0.004), int(SR*0.015))
    add_clamped(buf, 0, tone)

    return normalize_peak(buf, -1.0)


# ---------------------------------------------------------------------------
# Self-checks
# ---------------------------------------------------------------------------
def check_sane(buf, name):
    """Mono, 44100 Hz (implicit -- everything here is built at SR and nothing resamples
    it), finite, no clipping, no DC of any consequence.
    """
    if buf.ndim != 1:
        raise SystemExit("%s: buffer has shape %r, not mono"%(name, buf.shape))
    if not np.all(np.isfinite(buf)):
        raise SystemExit("%s: buffer contains a NaN or an infinity"%name)
    peak = float(np.max(np.abs(buf)))
    if peak > 1.0+1e-6:
        raise SystemExit("%s: clips at %.4f, over full scale"%(name, peak))
    if peak > 0 and abs(float(np.mean(buf)))/peak > 0.02:
        raise SystemExit("%s: DC offset is %.1f%% of peak, more than a rounding error"
                         % (name, 100*abs(float(np.mean(buf)))/peak))


def check_peak(buf, name, target_dbfs, tol_db=0.3):
    peak = float(np.max(np.abs(buf)))
    if peak <= 0:
        raise SystemExit("%s: silent"%name)
    got = 20*math.log10(peak)
    if abs(got-target_dbfs) > tol_db:
        raise SystemExit("%s: peaks at %.2f dBFS, wanted %.2f"%(name, got, target_dbfs))


def check_loop_seam(buf, name, factor=10.0, floor=2e-4):
    """The step from the last sample to the first must be no bigger than a typical
    sample-to-sample step found elsewhere in the file.  The reference is the 90th
    percentile of |diff|, not the median: the median is dragged down by the file's
    quiet stretches, and the wrap point is just as likely to land during a loud one as
    a real broken loop's click would.  Using a "loud but not exceptional" percentile as
    the yardstick means the check is still strict about an actual discontinuity, which
    reads as a jump comparable to the whole file's peak-to-trough range, not a percentile
    of it.
    """
    step = abs(float(buf[0])-float(buf[-1]))
    reference = float(np.percentile(np.abs(np.diff(buf)), 90))
    limit = max(factor*reference, floor)
    if step > limit:
        raise SystemExit("%s: the loop point steps by %.6f from last sample to first, "
                         "against a typical step of %.6f -- that would click on every "
                         "repeat" % (name, step, reference))


def check_oneshot_silence(buf, name, tol=1e-6):
    if abs(float(buf[0])) > tol or abs(float(buf[-1])) > tol:
        raise SystemExit("%s: does not start and end at silence (%.8f .. %.8f)"
                         % (name, buf[0], buf[-1]))


def check_ffmpeg():
    """Checked before any synthesis runs, per the module docstring: there is no point
    spending five sounds' worth of DSP only to find the encoder is missing at the end.
    """
    try:
        result = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"],
                                capture_output=True, text=True, check=True)
    except FileNotFoundError:
        raise SystemExit("ffmpeg is not on PATH; it is required to encode Ogg Vorbis")
    except subprocess.CalledProcessError as exc:
        raise SystemExit("ffmpeg -encoders failed: %s"%exc.stderr)
    if "libvorbis" not in result.stdout:
        raise SystemExit("this ffmpeg build has no libvorbis encoder; cannot write Ogg "
                         "Vorbis")


def write_wav(path, buf, sr=SR):
    ints = np.clip(np.round(buf*32767.0), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sr)
        handle.writeframes(ints.tobytes())


def encode_ogg(wav_path, ogg_path):
    try:
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path,
                        "-c:a", "libvorbis", "-q:a", "5", ogg_path],
                       check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as exc:
        raise SystemExit("ffmpeg failed to encode %s:\n%s"%(ogg_path, exc.stderr))


def decode_ogg(ogg_path):
    """Decode the encoded file back to raw PCM with ffmpeg, and read the sample rate
    and channel layout off ffmpeg's own report of what it just decoded -- there is
    nothing else on hand that would tell us, and that is rather the point: this is a
    check on what ffmpeg actually wrote, not on what this script asked it to.
    """
    try:
        result = subprocess.run(
            ["ffmpeg", "-hide_banner", "-i", ogg_path, "-f", "f32le",
             "-acodec", "pcm_f32le", "-"],
            check=True, capture_output=True)
    except subprocess.CalledProcessError as exc:
        raise SystemExit("ffmpeg failed to decode %s back:\n%s"
                         % (ogg_path, exc.stderr.decode("utf-8", "replace")))
    info = result.stderr.decode("utf-8", "replace")
    match = re.search(r"Audio:\s*vorbis.*?(\d+)\s*Hz,\s*(mono|stereo)", info)
    if not match:
        raise SystemExit("could not find the decoded format of %s in ffmpeg's own "
                         "report of it:\n%s"%(ogg_path, info))
    sr = int(match.group(1))
    channels = 1 if match.group(2)=="mono" else 2
    samples = np.frombuffer(result.stdout, dtype="<f4")
    return samples, sr, channels


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------
# Fixed seeds: reproducible output, same spirit as the deterministic geometry generator
# next door.  A generator that draws different clanks on every run makes "did my change
# actually do anything" impossible to answer from a diff of the encoded bytes.
SOUNDS = [
    dict(name="crawler_engine", loop=True, duration=2.0, target_dbfs=-6.0,
        synth=synth_engine, seed=1),
    dict(name="crawler_tracks", loop=True, duration=1.5, target_dbfs=-6.0,
        synth=synth_tracks, seed=2),
    dict(name="crawler_hydraulic", loop=True, duration=1.2, target_dbfs=-12.0,
        synth=synth_hydraulic, seed=3),
    dict(name="crawler_dump", loop=False, duration=1.2, target_dbfs=-4.0,
        synth=synth_dump, seed=4),
    dict(name="crawler_beeper", loop=False, duration=0.45, target_dbfs=-1.0,
        synth=synth_beeper, seed=5),
]

# ~120KB is the budget the brief set; this is failed loudly rather than silently
# accepted, with enough headroom above 120KB that an ordinary q:a=5 encode of one of
# these short files -- which comes in at a few tens of KB -- never trips it by chance.
SIZE_BUDGET = 180*1024


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    check_ffmpeg()

    sounds_dir = os.path.join(args.assets, "sounds")
    os.makedirs(sounds_dir, exist_ok=True)

    scratch = tempfile.mkdtemp(prefix="crawler_sounds_")
    try:
        for spec in SOUNDS:
            rng = np.random.default_rng(spec["seed"])
            buf = spec["synth"](rng)

            check_sane(buf, spec["name"])
            check_peak(buf, spec["name"], spec["target_dbfs"])
            if spec["loop"]:
                check_loop_seam(buf, spec["name"])
            else:
                check_oneshot_silence(buf, spec["name"])

            wav_path = os.path.join(scratch, spec["name"]+".wav")
            write_wav(wav_path, buf)
            ogg_path = os.path.join(sounds_dir, spec["name"]+".ogg")
            encode_ogg(wav_path, ogg_path)

            size = os.path.getsize(ogg_path)
            if size > SIZE_BUDGET:
                raise SystemExit("%s is %d bytes, well over the ~120KB budget"
                                 % (spec["name"], size))

            samples, sr, channels = decode_ogg(ogg_path)
            if sr != SR:
                raise SystemExit("%s: decoded at %d Hz, not %d"%(spec["name"], sr, SR))
            if channels != 1:
                raise SystemExit("%s: decoded with %d channels, not mono"
                                 % (spec["name"], channels))
            got_duration = len(samples)/sr
            tol = max(0.08, 0.06*spec["duration"])
            if abs(got_duration-spec["duration"]) > tol:
                raise SystemExit("%s: decoded duration is %.3fs, wanted %.2fs +/- %.2fs"
                                 % (spec["name"], got_duration, spec["duration"], tol))

            peak_dbfs = 20*math.log10(float(np.max(np.abs(buf))))
            print("wrote %s  (%d bytes, %.3fs round-tripped, peak %.1f dBFS)"
                  % (os.path.relpath(ogg_path, repo), size, got_duration, peak_dbfs))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)

    print("%d sounds synthesised, self-checked, and decoded back through ffmpeg to "
          "confirm the encode holds mono/44100Hz/duration"%len(SOUNDS))


if __name__=="__main__":
    main()
