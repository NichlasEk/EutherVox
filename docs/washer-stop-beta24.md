# Beta 24: restore Stop after washer completion

2026-09-05. EutherVox 0.19.0-beta.24, versionCode 48.

Beta 23 correctly changed Samsung Run + Finish to finished, but the existing
Stop gates only allowed running and paused. Both Android and EutherWash now
also allow Stop from finished. The usual confirmation, physical remote-enable
preflight, fixed Ready command and device readback remain required.
The finished-state confirmation explains ending the remote programme so the
machine can release its door lock. Actual door lock state is not exposed in
our status projection; no separate unlock command or automatic stop was added.

All 88 backend tests passed, including Run + Finish -> Stop -> Ready readback
and rejection with remote control disabled. Android unit tests, lint and debug
build passed. Emulator UI tested Klar -> Stoppa -> confirmation -> Ja, stoppa;
the synthetic server received exactly washer.command stop with confirmed=true.
No physical washer control was used for this verification.

Backend deployed on 192.168.32.186; beta24 published via EutherOxide Apps.
APK /home/nichlas/EutherVox-0.19.0-beta24-debug.apk SHA-256:
8aafc1d1d6efb3ea74643e1fe560177fdb04f0c5324c325d2bf2c69a82a656af

Physical acceptance: install beta24, with Klar and remote control still enabled,
press Stoppa and confirm; check that the machine returns idle and releases its
door normally. Automatic release needs a separate end-of-remote-cycle policy
and verification of machine behaviour; current fix restores the manual path.
