# HP printer integration: beta 25

2026-09-06. Android 0.19.0-beta.25, versionCode 49.

The printer card shares EutherShould's ten-minute on-demand cache. Printer
address and model checks remain server-side. Gateway access uses its configured
user allowlist and a private service token over HTTPS to EutherShould. The
existing authenticated EutherOxide WebSocket identifies the user; Android never
receives the service token. The gateway's maximum WebSocket message size is
14 MiB to accommodate a base64 PDF capped at 10 MiB.

Voice phrases:
- Hur mår skrivaren? / Hur mycket toner finns kvar? — speaks cached status.
- Skriv ut min PDF — opens printer card and Android's PDF picker.
- Skanna ett dokument — opens confirmation for a single A4 page on the glass.
- Visa mina utskriftsjobb — loads the user's recent EutherVox print jobs.

The chosen PDF filename is shown before Ja/Nej confirmation. One copy is sent
via IPP Print-Job. Scanning requests a color 300 dpi PDF through eSCL and offers
Android's document save picker. Cancellation is limited to a tracked job with
matching current printer ownership/name and has a confirmation. No firmware or
network settings are exposed. Scan-from-feeder, shared files on EutherOxide,
multiple copies and duplex selection are not implemented in this first slice.

Live capability discovery verified IPP Print-Job, Cancel-Job,
Get-Job-Attributes/Get-Jobs, PDF and duplex support. eSCL ScannerCapabilities
confirmed platen and feeder capabilities. This is capability evidence, not proof
that a physical print or scan has completed through the new application.

Validation:
- Android unit tests, debug build and lint passed.
- EutherShould's 50 tests cover auth, identity, cache, command confirmation,
  uncertain print deduplication, foreign scan-location rejection, IPP framing,
  job ownership and scan PDF return.
- Entire gateway suite: 166 passed, including Swedish deterministic voice
  routing and authenticated status projection.
- Android emulator: incoming scan action opened confirmation; confirmed scan
  returned a synthetic PDF which saved byte-for-byte correctly. Selecting a PDF
  opened filename confirmation and sent the PDF to the mock gateway.
- Live gateway client retrieved sleeping/toner status and an empty private job
  list through the deployed HTTPS service. Anonymous service access returned401.
  A 2 MiB unconfirmed upload reached the service and was rejected409, without
  sending a physical print command.

Release APK SHA-256:
4e2202dc8490ca42b29b6aca27cf4b9fdcb1e4091ed00110e9919f0f825ccc28
Server: /home/nichlas/EutherVox-0.19.0-beta25-debug.apk.
EutherOxide Apps and latest download alias point to beta25. Prior beta24 route
remains separate. Runtime gateway and EutherShould were restarted successfully.

Acceptance: install beta25 from Apps. Ask for printer status, choose an innocuous
PDF and confirm printing, then place an innocuous page on the glass and confirm
scanning. Save the returned PDF. Real paper/scan/audio behaviour remains a user
acceptance check; no physical print or scan was initiated during development.

Protocol references:
https://www.pwg.org/ipp/ippguide.html
https://github.com/alexpevzner/sane-airscan/blob/master/airscan-escl.c
