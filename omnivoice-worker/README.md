# OmniVoice in EutherVox

Two Swedish voices (`omnivoice-djup` and `omnivoice-klar`) use the original
references approved in the 2026-10-10 listening experiment. Synthesis is directly
OmniVoice on CUDA, not the experimental MOSS conversion. The existing Vox PCM
router and Android voice preference carry the selection.

## Install

Run `voice-lab/setup.sh` for the pinned environment and model weights. Place the
two approved reference WAVs and `manifest.json` in `$OMNIVOICE_ROOT/voices`.
The manifest maps `siaren-djup` and `siaren-klar` to `file`, `text`, `seed` and
`sha256`. Files must remain directly inside that directory and match their
checksums. Never regenerate an approved reference silently.

Install `deploy/euthervox-omnivoice.service` as a user service and restart the
Vox gateway using `config.real-beta.example.toml`. The worker binds only to
127.0.0.1:8796. Authentication remains at the existing Vox gateway boundary.
`GET /health` reports worker availability, busy state and model residency;
availability does not mean the model is preloaded or GPU capacity guaranteed.

## Resource lifecycle

The model loads on demand in a disposable child process, reused for subsequent
requests. After 120 seconds idle the process exits and releases GPU memory.
Only one request runs at once; competing calls get 429 rather than an unbounded
queue. Invalid profiles, languages and text over 600 characters are rejected.
Rendering is limited to 90 seconds, and a disconnected client terminates the
child. GPU loading requires 6 GiB free and caps PyTorch allocations. No other
service is stopped. A worker failure uses Vox's existing Piper fallback before
any audio has been sent. Reference speech and request text are not logged.

`systemctl --user stop euthervox-omnivoice` stops the worker and its renderer.
The first response after unloading is slower; measured here at 28.5 seconds
including imports/model loading, versus 2.1 seconds for a warm test sentence.

## Verification

```sh
.venv/bin/python -m pytest omnivoice-worker/test_worker.py server/tests/test_tts.py -q
```

Live verification covers both profiles through HTTP and the actual Vox router,
mono 22050 Hz PCM framing, idle release and client cancellation. Android beta38
adds the two voice choices; physical phone playback remains a separate check.
