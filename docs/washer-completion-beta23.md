# EutherVox beta 23: washer completion and automatic status

2026-09-05. Android version 0.19.0-beta.23, versionCode 47.

The live Samsung returned operational state Run, phase Finish, 100% and one
minute remaining after completion. EutherWash now treats Run with an explicit
completion phase as finished; percentage alone never indicates completion.
Ready with a stale Finish phase remains idle. Known course identifiers use the
existing program catalogue (Table_02_Course_1B is Bomull).

EutherVox polls status every five seconds while running/paused or scheduled,
and every fifteen seconds otherwise, using its existing background connection.
A missing response marks displayed status stale after twelve seconds. The UI
shows the last reading time, actual active program and temperature, and Klar.
The gateway independently polls for completion and queues its jingle/speech.

Validation: all 86 EutherWash tests passed; Android unit tests, debug build and
lint passed; seven gateway completion-monitor tests passed. Android emulator
mock transport observed status requests at 5.01 seconds while running, then
15.01 seconds after finished. The washer UI displayed Klar without manual
refresh. No physical washer command was sent for testing.

The deployed backend on 192.168.32.186 now reports finished and Bomull for the
same live appliance. The existing gateway detected that transition and queued
a completion message for android-phone. At 19:39:35 CEST the gateway logged
washer_notification_delivered for that event (b51889db-a5ed-4bb0-a79b-66fb22e46bec),
using moss-nano. Transport delivery is verified; physical phone audibility
remains a user check.

APK SHA-256:
a7766e1a9ec0dc3f8bbcf983c0d7790ea1dfbeb08bf580e11f9fa5afdfe371de

Server artifact: /home/nichlas/EutherVox-0.19.0-beta23-debug.apk.
EutherOxide Apps and /downloads/EutherVox.apk point to beta23; beta22 has a
separate legacy route. Server source backups are in
.euther-host/backups/euthervox-beta23-20260905/.

Install beta23 from the authenticated Apps card and keep background mode on.
During a wash, verify advancing reading times, then Klar and the jingle when
finished, including with the screen locked. Android audio volume and battery
settings still apply.
