# EutherVox TV apparater integration — 2026-10-10

The Android feature branch `feature/tv-apparater` in `/home/nichlas/EutherVox`
provides the **TV apparater** tab in 0.20.0-beta.1 (version code 65).

## Verified deployment topology

The public EutherOxide/Caddy server is 192.168.32.186. It authenticates the
WebSocket route and forwards the verified user to the EutherVox gateway on
192.168.32.88:8788. The gateway runs as the local user service
`euthervox-gateway.service`, not on .186. It connects to this node at
192.168.32.248:443 using the private certificate pin and node token. DHCP
reservation is recommended. No ESP32 port is exposed publicly.

Only configured users arriving through configured trusted proxy IPs can use
remote requests or voice routing. A direct LAN client supplying a forged user
header is rejected for this feature. EutherID is reused through the existing
EutherOxide authentication path; no invented standalone EutherID API is used.

## User workflow

1. Connect/login in EutherVox and select **TV apparater**.
2. Name the appliance/button and enter one or more spoken phrases separated by `;`.
3. Start recording and press the original remote briefly within 60 seconds.
4. Inspect detected protocol and pulse count; explicitly save the signal and phrase.
5. Enable the optional backup buttons, test once, and report whether the appliance reacted.
6. Use the push-to-talk control and say the registered phrase.

The gateway matches whole normalized phrases, not arbitrary model instructions.
Each phrase executes a single registered action. Existing Logitech volume up/down
commands are seeded. The ESP32 stores up to eight additional raw signals in NVS;
user ownership, labels, aliases, execution history and observations live in the
gateway's private SQLite database. A captured signal can be reviewed for five
minutes before saving. Carrier is assumed 38 kHz from the receiver, not measured.
Long air-conditioner frames and protocols needing toggle-state synthesis are not
fully supported by this raw replay path.

The gateway reserves each execution ID durably before enabling the node. It
executes once, then attempts disable even after an uncertain result. It never
retries an uncertain transmission. Node replay protection uses a random epoch
and 64 non-evicting request entries. Before capacity is exhausted, the gateway
explicitly disables the node and rotates its epoch. Old requests remain invalid;
the gateway's durable request IDs continue to prevent duplicates across rotation.

## EutherBeam

The existing EutherBeam Android source is included as a library with upstream
MIT attribution, preserving Samsung, NEC and Android TV setup and backup controls.
It uses the phone's local network and saved paired targets. Standalone-app
credentials cannot be imported from its Android sandbox: pair again inside Vox.
Fixed voice operations map to saved targets only; generated addresses or keys
are not accepted. Examples: “sätt på Samsung”, “sänk volymen på Android TV”,
“HDMI ett på NEC”. Custom IR phrases take precedence. Existing gateway NEC control
remains available in a separate expandable section for use away from home.

An IR `transmitted` acknowledgement does not confirm appliance state. The UI
stores the user's confirmation separately. Beam network acknowledgements also do
not prove physical state. Real TV pairing and spoken end-to-end response must be
verified with the user's devices.

## Validation and limitations

- Gateway suite: 288 tests passed, including ownership, exact phrase routing,
  persistent duplicate protection, uncertain-send handling and trusted-proxy checks.
- Android app/Beam unit tests, app lint and debug build pass.
- Emulator: mock receive → inspect → save → test → user confirmation completed;
  this does not constitute a physical transmission test.
- Live gateway through the trusted server: registered commands list succeeds;
  unapproved user and forged direct-LAN identity fail.
- New ESP32 firmware compiled and flashed with esptool hash verification.
- Live disabled-node checks reject wrong token/pin, stale epoch, conflicts,
  oversized requests and unregistered operations. Epoch renewal rejects old IDs.
- Earlier physical Logitech volume-down was user-confirmed. New app learning,
  phone voice and TV pairing require physical follow-up.
- Multi-node configuration/discovery, node OTA/rollback, flash encryption and
  secure boot remain future work. The current implementation is a single-node beta.

## Direct Logitech buttons — 0.20.0-beta.2

Two always-visible buttons, **Höj volymen** and **Sänk volymen**, sit above the
voice control on the TV card. They do not require enabling backup controls.
Each tap invokes the gateway's `logitech` operation with `direction` (`up` or
`down`) and a fresh request ID. The gateway resolves the current authenticated
user's registered Logitech command; ownership and durable duplicate checks remain.
Buttons are disabled while a request is pending. There is no hold-to-repeat.

Standalone EutherBeam 0.1.0-alpha.13 uses an explicit ordered broadcast to
`LogitechVolumeReceiver` in Vox. The receiver is protected by a signature-level
permission and accepts only the two directions. Both apps must be signed by the
same trusted publisher (the current beta builds use the same development key).
Vox must already be connected/authenticated; the bridge does not queue actions
for a later reconnect or share server/node credentials with Beam. An 8-second
bridge timeout returns uncertainty without resending; the existing controller
and gateway retain their own busy/deduplication safeguards.

Validation: app builds/lint/unit tests pass, 9 remote-service tests pass,
emulator taps on both buttons in both apps reach the mock transmitter exactly
once per tap and display the result. An unprivileged ADB broadcast was denied
by the signature permission. No physical IR was emitted during these tests.

## Fast volume update — Vox beta.3 / Beam alpha.14

This supersedes the earlier acknowledgement-waiting button workflow. Logitech
buttons dispatch immediately, with 160 ms tap debouncing and no success receipt,
waiting state or automatic resend. Beam uses an unordered signature-protected
broadcast; Vox's already-authenticated WebSocket sends the registered operation.
The gateway sends one HMAC-authenticated, short-lived UDP packet over LAN. It
keeps a durable duplicate ledger and refreshes the node's epoch/clock outside the
button path. Logitech voice aliases also dispatch silently. Local transport
errors may still be reported; silence is not proof of physical device response.
Update both apps and node firmware together. The corrected firmware includes one
observed NEC repetition frame; the previous claim that volume changes were
confirmed has been withdrawn by the user. At-TV verification is pending.

## Natural volume routing — 2026-10-10

The user now confirms the fast IR/app update works at the TV (“Det funkar kanon!”).
This supersedes the pending physical trial above. Two TV installations have
Logitech systems accepting the same codes; the destination must therefore select
an IR node, not another code. Room mapping and the second node are not inferred.

The gateway supports optional `[remotes.tv_targets.<id>]` tables with `label`,
`aliases` and either `use_default_node = true` (only one target) or a private
`node_config` path for another provisioned node. Omitting both leaves a named
TV unavailable; it never falls back to another target. This feature is inactive
until targets are configured. Learning and existing generic app buttons still
use the original default node; this is not a multi-node learning UI.

Example (room aliases must be confirmed before adding them):

```toml
[remotes.tv_targets.nec]
label = "NEC-TV:n"
aliases = ["nec", "nec tvn", "nec tv", "nec-tv:n"]
# use_default_node = true  # only if the existing transmitter is here

[remotes.tv_targets.samsung]
label = "Samsung-TV:n"
aliases = ["samsung", "samsung tvn", "samsung tv", "samsung-tv:n"]
# node_config = "/private/provisioned-samsung-node.json"
```

Supported whole-utterance requests include “höj volymen på NEC TVN tack”,
“kan du sänka ljudet på Samsung”, “skruva ner volymen lite”, “höj”, “sänk” and
“det är för högt”. Each means one bounded existing volume press. No model-generated
codes, unconstrained repeats, negated requests or substring matching are used.
Custom registered aliases retain precedence. A phrase like “det här är intressant”
is ordinary conversation, not a volume instruction.

An unnamed target uses the last explicitly selected TV in that authenticated
voice connection, expiring after ten minutes without a volume dispatch. Otherwise Vox asks which TV. A target-only
answer resolves the pending direction within 30 seconds; unrelated intervening
speech clears the pending question. An unavailable or ambiguous target clears
selection rather than silently using another room. Connection restart also clears
context. Success has no speech acknowledgement; only clarification/errors speak.

Validation: all 316 gateway tests pass, including 36 focused remote/volume tests, including two simulated nodes,
ownership, cross-target duplicate protection, unknown/unconfigured targets,
negation, silent dispatch, clarification, follow-up and context expiry. No real IR
was sent during these tests. Room/node configuration and live voice trial remain.

## Confirmed home mapping and activation — 2026-10-10

User confirms NEC + Logitech downstairs, Samsung + Logitech one flight upstairs,
and only one ESP32 so far. The deployed gateway configuration assigns the default
node only to `nec`. Its aliases include bottenvåningen, nedervåningen, nere and
där nere. `samsung` recognizes övervåningen, uppe, där uppe and en trappa upp,
but has no node configuration until the second transmitter is built. Requests
for Samsung volume explain the missing transmitter and do not send downstairs.
Direct room phrases such as “sänk ljudet nere” and “höj volymen uppe” are supported.
The original app volume buttons still operate the downstairs/default node.
No Android update or ESP32 reflash is required for this voice routing change.

All 45 focused remote/volume tests pass, including nine cases using the actual
configured room aliases and node assignments. The gateway user service was
restarted to activate this configuration. These tests emitted no real IR; spoken
phone-to-device verification remains for the user.

## Separate TV buttons and themes — Vox beta.4 / Beam alpha.15

Each app now shows two named Logitech cards: NEC · Bottenvåningen and Samsung ·
Övervåningen, each with explicit up/down buttons. Every new request includes the
registered `target_id`; no selection can silently carry over from another card.
160 ms tap debounce is per target and success remains silent/no acknowledgement.
Standalone Beam uses the signature-protected `LOGITECH_VOLUME_TARGET` action.
Older Vox receivers reject this new action instead of ignoring the target and
sending downstairs; update both apps. New Vox still accepts the old action as NEC.

Vox cards use the active Material color scheme and shapes; embedded Beam's palette
now follows Vox including System Regis. Standalone Beam's Material controls use
its existing dark/mint palette. No credentials, MAC addresses or IPs are sent by
the app. The gateway maps IDs to separately pinned certificates and unique tokens.

The new ESP8266 is assigned to Samsung, at observed DHCP address 192.168.32.253,
MAC 80:7d:3a:3e:20:fc. It is temporarily placed downstairs for the user's first
optical test. The NEC target remains assigned to ESP32 at 192.168.32.248; it was
unreachable during this turn's live status check. Clock refreshes now run
independently per node so an unreachable node cannot stall the other one.
The new node's pinned status refresh succeeds and TX is ready. No actual IR was
sent by the development tests. Physical volume response on ESP8266 remains pending.

Validation: 47 focused gateway tests pass, including explicit-target dispatch,
unknown-target rejection and independent node refresh. Both APK builds, lint and
unit tests pass. Emulator: all four buttons in each app dispatch the exact target
and direction once through two mock nodes with no response/wait. System Regis,
classic Vox and standalone Beam colors were inspected. Packages are published as
Vox 0.20.0-beta.4 (68) and Beam 0.1.0-alpha.15 (15). Server upload hashes match local
APKs; Beam's public download hash matches, Vox's anonymous download returns 401.

## Physical ESP8266 volume trial confirmed — 2026-10-10

After being asked to test volume down and up on the Samsung · Övervåningen card
with Vox 0.20.0-beta.4 and Beam 0.1.0-alpha.15, the user confirmed that it works.
This verifies actual Logitech volume control through the new ESP8266 transmitter,
which was temporarily positioned downstairs. It supersedes the pending physical
trial notes above. Testing at its final upstairs mounting position remains to be
done after the user hangs the nodes in place.

## Room selection and ordinary volume controls — Vox beta.5 (2026-10-11)

Vox 0.20.0-beta.5 (69) adds a persistent Uppe/Nere/Ingen room selector above the
main navigation. Uppe maps to Samsung and Nere to NEC. Each audio.start carries
a snapshot of the selection, including an empty value when cleared. Room changes
clear older volume follow-up context. Explicit spoken destinations take priority
for that command; the app selection remains the default for the next command.

TV power/input commands use the display; playback/navigation commands use exactly
one saved Android TV puck linked to that display. Missing or ambiguous puck links
fail rather than selecting the last-used puck in another room. Play and pause use
separate Android keycodes, avoiding play/pause toggles. The existing authentication
and trusted-proxy gates remain in place. This release scopes TV, puck and volume
commands; lights, pumps and other devices do not yet consume the room preference.
Film search, availability and now-playing metadata are not implemented here.

The TV tab has Uppe/Nere cards with display power/input, player remote, then normal
Volym minus/plus buttons at the bottom of each card. Volume retains the registered
NEC/Samsung IR routing and 160 ms per-target debounce. Hardware and Logitech labels
are absent from these controls. Pairing remains available in the expandable Beam
panel, which does not repeat the floor cards when embedded in Vox.

Validation: 115 focused gateway tests passed, including the complete mocked
speech pipeline, explicit overrides, room changes, session isolation and invalid
room rejection. App/Beam JVM tests, app lint and APK build passed. Emulator taps
on all four volume buttons produced exactly Samsung down/up and NEC down/up at a
local mock gateway, with no physical transmission. Room selection survived an
app restart. Screenshot: build/reports/room-controls/nere.png. The local gateway
service was restarted and reached engines_ready/listening on port 8788.

APK: build/releases/EutherVox-0.20.0-beta5-debug.apk
SHA-256: 7ffbe529e93b85484f578f5ff5207a3d5a749162b4cea2cd7578ae26da50ae0e
The APK has not been published to EutherOxide Apps or installed on the user's
phone. Physical room/puck behavior remains to be tested with the paired devices.

## TV combinations and film search — Vox beta.6 / Beam alpha.16 (2026-10-11)

Uppe/Nere are TV combinations only: Samsung/NEC display, its explicitly linked
Android player and the existing server-owned volume transmitter target. Other
appliances retain their existing rooms. The selector is labelled TV-rum.
Both apps now display the linked player's name and pairing state on each card,
with a Välj puck menu. New discoveries are unassigned. Selecting a player cannot
silently move it from the other card; disconnect that binding first. Legacy
ambiguous links require an explicit choice. Setup and cards observe saved changes.

Vox understands “Jag vill titta på Terminator på NEC tvn”, “Jag vill se Terminator
på Netflix” with a selected TV room, and “vad tittar jag på?”. An explicit target
wins over the selected TV room. Missing/conflicting targets request clarification.
The gateway synthesizes only a bounded search phrase and converts it to 8 kHz mono
PCM with ffmpeg. The phone sends SEARCH, waits for the puck's voice session ID,
then sends bounded PCM chunks and a voice-end packet. There is no automatic
retry. The puck's own search/assistant handles the results; this can involve
Google and the named streaming service. No availability/subscription checks or
guaranteed direct film playback are claimed. TV power/input remain separate
controls; this release does not guess which HDMI input the player uses.

“Vad visas?” reads Cast receiver/media status without LAUNCH or LOAD. If Cast
exposes a title, its actual player state is reported. Otherwise the optional
Android Remote IME app identifier is reported, with title explicitly unknown.
Native streaming apps need not expose Cast metadata. Results return to Vox and
are spoken while preserving the active utterance. Media capabilities are negotiated
only for these operations; existing remote-key connections retain their features.
Older Vox apps are told to update instead of receiving unsupported media actions.

Protocol references:
- https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto
- https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py
- https://developers.google.com/cast/docs/media/messages

Validation: 125 focused Python tests pass. Vox has 57 passing JVM tests; embedded
and standalone Beam each have 28 passing JVM tests and 5 opt-in hardware tests
skipped. Both APKs build and lint passes. An emulator with a local TLS Android TV
simulator verified SEARCH, session 77, 20480/11520-byte chunks and voice end;
Cast status returned simulated Terminator/PLAYING through GET_STATUS only. The
phone accepted and completed the returned TTS stream. Standalone Beam's four
volume buttons reached the signature-protected Vox receiver and mock gateway as
Samsung down/up and NEC down/up exactly once. No real IR or TV action was emitted.
Physical search behavior and title availability on the user's pucks remain open.

Packages (local, not published to EutherOxide Apps):
- Vox 0.20.0-beta.6 / 70: build/releases/EutherVox-0.20.0-beta6-debug.apk
  SHA-256 05a232a34cfbb80c4c02ddcb4d346e0e129717d823e8fd1d04e85e706f060f19
- Beam 0.1.0-alpha.16 / 16: /home/nichlas/EutherBeam/build/releases/EutherBeam-0.1.0-alpha16-debug.apk
  SHA-256 a9a4597cc49e7fe00c82db0a268924d982de56d0b9bdec14596000f461666685

The final emulator run also reached audio_playback_start, turn.tts_complete and
turn.idle for the media-result utterance. Gateway activation uses the local
`euthervox-gateway.service` (the server at .186 remains the authenticated proxy).

## Beta 7 / Beam alpha 17: shared remotes and explicit power results

The installed standalone Beam is now the execution owner when its same-signature
control provider is available. Vox delegates control, search and playback status
to that provider, and TV room cards load/save player bindings through it. Pairing
keys stay in Beam's Android Keystore; they are never exported. Without this
provider, the embedded standalone-compatible controls retain their local store.
Install both new versions to reuse existing Beam pairings in Vox.

TV power phrases include `sätt på TV`, `stäng av tv:n`, `starta televisionen` and
polite infinitives. An unscoped TV request asks Uppe/Nere and accepts a bounded
room/display reply for 45 seconds (including NEC-TV). Explicit destinations still
win over the selected room. New clients advertise `tv_control_result_v1`, retain
the active utterance, and speak the actual phone action result or timeout.

Player wake now tries the paired Android Remote WAKEUP key before Cast fallback.
Cards provide Väck puck/Vila puck separately from display power. Successful
transport is described as a sent command, not proof that the hardware woke.
Network-off standby still requires a device-specific wake mechanism.

Validation: 131 focused gateway tests; both Android builds, JVM tests and lint.
Emulator: Vox's Beam preferences removed, standalone Beam alone held a simulated
paired Nere player. Voice control and room-card buttons reached the simulator
through the real signature-protected provider, sending WAKEUP 224 and SLEEP 223.
Vox displayed Beam's player and paired state. Shell access to the provider was
denied. No physical TV/puck was controlled during these tests.
