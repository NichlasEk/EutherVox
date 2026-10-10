# Scryer voice lab

An isolated local experiment: design an original Swedish voice with OmniVoice,
then clone its reference with both OmniVoice and the existing MOSS-Nano CPU
adapter. This does not register voices or change the running Vox services.

## Run

Requires `uv`, Python 3.12, the existing `moss-worker/.venv` and MOSS ONNX weights.
Model weights, environments and generated audio stay outside Git on Kingston.

```sh
voice-lab/setup.sh
voice-lab/run.sh /run/media/nichlas/Kingston1TB/models/OmniVoice/experiments/my-new-test
```

Set `OMNIVOICE_ROOT` to change the default model/environment directory.
Setup pins upstream source and model revisions, verifies published LFS hashes,
and records local SHA-256 checksums. Run uses offline model loading and refuses
to overwrite an experiment. Each inference process has a ten-minute timeout.
The GPU stage requires 6 GiB free and caps PyTorch allocations; it never stops
another service. GPU memory is released before the CPU cloning stage starts.

Open the experiment's `index.html` for the six listening samples. `design.json`
and `moss.json` record seeds, reference checksums, sample durations and measured
generation times. MOSS first-audio latency includes reference processing for
that candidate; these are not warm production latency benchmarks.

## Listening gate

Both references are newly synthesized characters, not imitations of real people.
Voice design is primarily trained on English and Chinese, so Swedish quality
must be judged by listening. Check pronunciation, intonation, intelligibility
and preservation of voice identity before choosing a voice for Vox. Neither
successful synthesis nor an ASR transcript establishes natural Swedish speech.
The reference transcript supplied for cloning must match the generated speech.

## Checks

```sh
.venv/bin/python -m pytest voice-lab/test_chain.py -q
```

Sources: [OmniVoice](https://github.com/k2-fsa/OmniVoice) and its
[voice-design documentation](https://github.com/k2-fsa/OmniVoice/blob/main/docs/voice-design.md).

## First measured experiment: 2026-10-10

Local artifacts: `models/OmniVoice/experiments/scryer-20261010-01` on Kingston.
RTX 4090 and Xeon E5-2697 v3; MOSS used eight CPU threads.

| Candidate | OmniVoice clone wall/audio | MOSS first audio | MOSS wall/audio |
| --- | --- | --- | --- |
| siaren-djup | 2.22 / 5.08 s | 1.15 s | 10.25 / 14.80 s |
| siaren-klar | 1.98 / 4.54 s | 0.62 s | 3.52 / 4.48 s |

OmniVoice model loading took 9.95 s; peak PyTorch allocated GPU memory was
2126 MiB (not total driver memory). All six WAVs loaded in Firefox.
Faster-Whisper small transcribed both OmniVoice clone sentences exactly.
The deep MOSS candidate's transcript showed repetitions and substantial word
errors; do not promote that candidate based on this run. The clear MOSS sample
was much closer, with minor transcription differences. The deep reference's
opening also differed in ASR, so its reference transcript needs listening
verification before reuse. No subjective accent or voice-identity pass is claimed.

If an experiment contains `asr-check.json` with `samples` entries containing
`file` and `transcript`, rerun `compare.py EXPERIMENT` to show those transcripts
beside the audio. They are evidence for review, not a quality score.
