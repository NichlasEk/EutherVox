# KittenTTS 2 for Vox

This optional worker adapts the official CPU C++ runtime to Vox's existing
`http_pcm` protocol. Model assets, CPU LibTorch, build tree and environment live
under `/run/media/nichlas/Kingston1TB/models/KittenTTS-2`, outside Git.

Upstream: https://github.com/KittenML/kitten-tts-2-cpp
Model: https://huggingface.co/KittenML/kitten-tts-2

`bash kitten-worker/setup.sh` installs the locked Python dependencies, checks
out the pinned C++ revision, builds without CUDA and downloads only the required
native assets at a pinned model revision. The download manifest records sizes
and SHA-256 hashes. Default assets total 1,602,623,375 bytes: a ternary GGUF,
a waveform decoder and precomputed built-in voice conditioning. The smaller
506 MiB Python weight variant is not the C++ model used here.

The worker binds to loopback port 8794. It runs one synthesis at a time with
eight CPU threads, a 75-second child-process limit, a 600-character request
limit and bounded output. Unfinished generation is rejected, not played as
complete speech. Incoming text is passed as an argument without a shell.
No reference-file paths or arbitrary model options are accepted over HTTP.
Audio is resampled from native 24 kHz to Vox's existing 22050 Hz mono PCM.
English text normalization is disabled so it cannot rewrite Swedish numbers
and abbreviations as English. Voice cloning is not exposed by this adapter.

Service: `deploy/euthervox-kitten.service`. The model disk must be mounted.
The systemd unit enforces CPU and memory limits and uses a CPU-only Torch build.
Run isolated worker tests with `models/KittenTTS-2/venv/bin/python -m pytest
kitten-worker/test_worker.py` (use the absolute model-root path).

The upstream overview advertises Swedish, but its detailed voice guide and
actual native voice list contain no Swedish preset. Do not infer Swedish
quality from the overview's language count or describe advertised CPU realtime
performance as measured performance on this host.

The model card identifies the weights as Stellon Labs Community License;
the wrapper/library license is separate. Model weights are not bundled in the APK
or committed to Vox. The downloaded model card and license are retained beside
the local assets.

## Measured on the owner host (2026-10-10)

Intel Xeon E5-2697 v3, eight runtime threads, built-in Bruno voice, default S3
decoder, seed 1234. A Swedish 89-character sample produced 6.34 seconds of
24 kHz speech in 22.56 seconds wall time. Native model/decoder time was 15.58
seconds (RTF 2.46); process startup/model loading adds further latency. This
host does **not** achieve realtime. A second sample through Vox's actual PCM
router produced 4.74 seconds of 22050 Hz audio in 18.98 seconds; fallback was
disabled for that probe so Piper could not conceal a failed Kitten request.

Whisper-small recognized the overall Swedish sentences, with errors such as
Siaren/Ciaran, berättar/Beretta and provröst/provrest. This is evidence of basic
intelligibility, not a human listening-quality verdict. The UI explicitly marks
the voice as slow and experimental. Existing Piper NST remains the default
and the router's normal fallback. Short action acknowledgements continue to use
the existing fast voice path.

Eight worker tests cover request validation, serialization, process timeout,
PCM resampling and rejection of incomplete generation. All 241 gateway tests
and 54 Android JVM tests pass. Physical phone listening remains a separate test.

The Android emulator also exercised selecting/saving KittenTTS 2 and Scryer
report playback through the real Kitten worker, with isolated simulated report
data. A 164-character report rendered in 28.0 seconds into 10.82 seconds of
audio; Android recorded first frame, audio playback start, last frame and
completion. Test preferences were restored afterward.

Beta 37 APK SHA-256:
`eb5cd59535534e52c46bc82728d85fa24faebb77b9159ec78ba95366c40d7583`.
