# Beta 26: background battery use and independent phone delivery

Version 0.19.0-beta.26 (50) defaults to battery-saving background operation.
The foreground service retains the connection but no longer holds a partial wake
lock continuously. Listening, processing, speech and printer operations acquire a
bounded two-minute lock, released when the operation finishes. Uncheck
**Batterisnål bakgrund** in voice settings to restore continuous CPU wakefulness.
Android's background battery exemption is a separate setting.

Washer status polling runs only while the activity is resumed (5 seconds while
active/scheduled, 15 otherwise). Returning to the app refreshes immediately.
The gateway still monitors completion independently. Reconnect backoff grows to
120 seconds in the background, versus 8 seconds in the foreground; returning to
the app interrupts a pending retry delay. The service updates its notification
only when its displayed connection text changes.

Doze can delay network reception in battery-saving mode. A successful write to a
socket is not proof of audible playback: the existing server queue is transport
acknowledged, not playback acknowledged. No battery percentage improvement or
GrapheneOS hardware result is claimed. Compare normal daily usage on the phones;
for immediate-message diagnosis use unrestricted Android battery permissions and
turn off Batterisnål bakgrund temporarily.

Each installation now persists a random notification_device_id (app backups are
disabled). The gateway hashes the authenticated user plus this ID for the washer
queue, leaving the human node name and device-action routing unchanged. Legacy
clients fall back to their node name. Previously two installations with the
android-phone default shared a queue: the first successful delivery consumed the
message. Install beta 26 on both phones and open each once to register its queue.
The live registry before this release contained only android-phone; that supports
this failure mechanism but does not prove why GrapheneOS was silent.

Validation: Android unit tests, debug APK assembly and lint; all 167 gateway tests,
including independent delivery to same-name phones and identity on reconnect.

Emulator API 30: synthetic six-second audio reached playback while Home was
visible and with the screen asleep. EutherVox's wake lock was absent while idle,
held during reception/playback and released after completion. Foreground washer
requests resumed and repeated after five seconds; none repeated during the
background observation. Continuous mode retained its idle wake lock; switching
back released it. Installation identity survived the application restart.
APK SHA-256 is recorded with the release verification, not fixed in this document.
