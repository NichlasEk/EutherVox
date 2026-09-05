# EutherVox beta 21: tvättmeddelanden i bakgrunden

2026-09-05. Android version 0.19.0-beta.21, versionCode 45.

## Rättning

EutherVox hade en bakgrundstjänst men den var avstängd som standard, även när äldre inställningar sparades. Dessutom avbröt Activity.onPause ett pågående husmeddelande när skärmen låstes.

- Bakgrundsanslutningen aktiveras en gång vid uppgradering. Därefter respekteras avstängning i inställningar eller genom tjänstens Koppla från-knapp.
- Connected-device foreground service håller den lokala husanslutningen igång med en partiell wake lock som släpps när tjänsten förstörs. Mikrofonen används inte för detta.
- BOOT_COMPLETED och MY_PACKAGE_REPLACED återstartar en konfigurerad, aktiverad nod. Endast connectedDevice används vid tjänstestart eftersom Android 15+ förbjuder mediaPlayback-start från BOOT_COMPLETED.
- Skärmlåsning avbryter fortfarande användarens röstkonversation men låter ett inkommande husmeddelande spelas klart.
- Appen visar en knapp för Androids batteriundantag. Användaren måste godkänna detta på telefonen för kontinuerlig lokal anslutning i Doze.

## Installation

EutherOxide → Apps → EutherVox. Installera uppdateringen, öppna den en gång och tryck på **Tillåt tvättmeddelanden med släckt skärm** om knappen visas. Godkänn Androids dialog och notisbehörighet. Bakgrundsnoden ska visas som aktiv och ansluten.

Tvångsstopp i Android stoppar appen tills den öppnas igen. Nätverksanslutning till EutherVox-servern behövs. Ingen riktig tvättcykel startades i testerna.

## Verifiering

- Gradle testDebugUnitTest: 44 tester, 0 fel.
- assembleDebug och lintDebug: godkända; lint 0 errors, 14 warnings, 7 hints (inklusive batteri-API-varningar för den avsiktliga lokala husanslutningen).
- Android 11/API 30-emulator, syntetisk WebSocket-server: uppgradering från sparad false migrerade till aktiv bakgrundstjänst.
- Test washer-doze-1: app i bakgrunden, mWakefulness=Asleep, deep IDLE, batteriundantag aktivt; audio_playback_start → turn.tts_complete → turn.idle.
- Test washer-lock-midplay: låste skärmen under ljudströmmen; hela meddelandet spelades klart utan cancellation eller fel.
- Omstart av emulatorn: tjänsten startade och nådde transport.ready utan att appen öppnades, efter Androids första upplåsning.
- Ingen fysisk telefon var ansluten. Android 15/16-beteende har inte körts på en fysisk enhet.

APK: dist/euthervox-beta21/EutherVox-0.19.0-beta21-debug.apk

SHA-256: `5a7524b987b8ae65c54dd9e81e2dc1c17631c270ea5220b2d64e16d668e85b75`

Signeringscertifikat SHA-256: `8e858eeb40bda465b5f3076faf54180828157aa53139ea5dd84ec734babf8831`, samma som beta 20.

Källändringarna finns i /home/nichlas/EutherVox. Serverns EutherOxide är källa för publiceringen; tidigare lokala serverändringar behölls. Serverbackup: .euther-host/backups/euthervox-beta21-20260905/. APK på servern: /home/nichlas/EutherVox-0.19.0-beta21-debug.apk, uppladdad och hashverifierad.

Android-referens: https://developer.android.com/training/monitoring-device-state/doze-standby och https://developer.android.com/about/versions/15/changes/foreground-service-types.

## Publicerat

Serverns `npm run build` och `cargo build --release` passerade. `eutherhost.service` startades om 2026-09-05 16:40:10 CEST och är aktiv. Arbetskatalogen är /home/nichlas/EutherOxide. Apps-paketet i dist innehåller v0.19.0-beta.21; releasebinären innehåller den nya versionsrutten. /downloads/EutherVox.apk väljer beta 21 och beta20-länken behåller sin gamla APK.

HTTP med rätt Host/HTTPS-proxyhuvuden: startsidan 200 (inloggningsvy), versionsnedladdningen 401 utan session. Den inloggade Apps-vyn och en autentiserad HTTP-nedladdning kunde inte verifieras i denna körning; APK-filen på servern är hashverifierad. Testemulator och syntetisk WebSocket-server är avslutade. Källändringar och dokumentation är inte committade eller pushade.
