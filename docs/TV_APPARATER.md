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
