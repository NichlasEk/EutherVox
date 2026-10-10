# Scryer reports through EutherVox

Implemented 2026-10-10, Android 0.19.0-beta.36 (60).
Backend paths below refer to the companion NichlasEk/EutherScryer repository.

The Scryer destination in Vox displays bounded inventory-backed problem reports,
evidence timestamps, related nodes, acknowledgements and one-hour attention
snoozes. It refreshes while open. Acknowledging never marks a problem resolved.
The **Läs upp lägesrapport** button and normal push-to-talk both reuse Vox's
selected voice and existing cancellable TTS stream.

Examples (punctuation and capitalization do not matter):

- Scryer, rapportera.
- Siaren! Säg mig läget.
- Ooo Orakel, ge mig din sanning.
- O du vise siare, berätta läget.
- Scryer, några problem?
- Oraklet, vad har du sett?
- Scryer status.
- Siaren, är allt lugnt?
- Orakel, hur står det till?
- Scryer, hur mår systemen?

These are report requests during normal Vox recording, not always-listening
wake words. Negative requests such as “Scryer, rapportera inte” do not trigger
a report. Recognition still depends on the existing speech-to-text engine;
Scryer/Siaren/Oraklet are included in its vocabulary hints.

Speech is a deterministic Swedish summary of the same records, with at most
three named open problems. It states inventory age, uncertain records and
acknowledged-but-unresolved problems. Missing, paused, old or incomplete data
cannot become a confident all-clear. No language-model call is needed.
Silencing attention does not hide a problem from a requested status report.
There are no unsolicited spoken announcements or push notifications.

## Lifecycle and persistence

`backend/scryer_reports.py` is used by the existing EutherNet ScryerWorld.
It stores at most 64 records in the same atomic state file. Failed status opens
a report immediately; degraded/offline requires two distinct inventory stamps.
Repeated polling of one snapshot never supplies new evidence. Explicit good
status resolves a report. Unknown/configured/disappearing nodes do not prove
recovery. A later confirmed recurrence increments the episode and clears the
old acknowledgement and snooze. Old state files without reports remain valid.
Reports and Ghost Nodes are separate collections; a problem is never a
speculative infrastructure entity. Observation does not run a fresh health
check or modify infrastructure.

## Narrow Vox bridge

The desktop Vox gateway authenticates using its normal account flow and then
requires the configured `[scryer].allowed_users` list (currently the owner).
It reads via an SSH identity dedicated to reports, cached for 30 seconds.
The server key must be constrained to the gateway source IP and forced command:

```
from="192.168.32.88",restrict,command="/usr/bin/python3 /home/nichlas/EutherNet/scripts/scryer_vox_bridge.py" ssh-ed25519 <public-key>
```

The gateway private key lives outside Git at
`~/.ssh/euther_scryer_reports`. Strict host-key checking is required. Never
reuse the administrator key. `scripts/enable_shared.py` copies the bridge
implementation but deliberately does not create keys or edit authorized_keys.
The forced command accepts only list, report-ack and report-snooze. It exports
only report fields, inventory timestamp and observer availability. It cannot
pause Scryer, inspect arbitrary files, run commands or operate services.

Disable Vox access with `[scryer] enabled = false` and restart the gateway;
remove the dedicated public key to revoke transport independently. Existing
Scryer pause/disable controls remain available in EutherVerse.

## Verification

- Scryer: 12 TypeScript and 29 Python tests, including lifecycle, evidence
  deduplication, bounded memory, restart persistence and bridge command denial.
- Vox: 241 gateway tests, including all three requested aliases through the
  transcript-to-audio path without an LLM, account denial and cancellation.
- Android JVM tests and debug build passed. Emulator UI exercised report fetch,
  acknowledgement, snooze and the complete button-to-audio-player path using
  isolated simulated reports. Production reports were not mutated for testing.
- Actual configured Piper voice synthesized a live read-only report: 13.6
  seconds of nonzero 22050 Hz PCM. Physical phone microphone recognition and
  speaker playback remain a separate owner test.

APK SHA-256:
`506633326c27cadfefb3177d911c2c48c0c68d12f7f5748a36a143b4189e0419`.
