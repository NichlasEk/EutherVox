# Handoff — Vox / Beam TV-styrning

Uppdaterad 2026-10-11, Europe/Paris. Läs denna fil först efter omstart.

## Kort nuläge

Vox **0.20.0-beta.7 / versionCode 71** och EutherBeam
**0.1.0-alpha.17 / versionCode 17** är byggda, testade och publicerade i
**Apps på https://apothictech.se/**. Båda apparna behöver uppdateras för den nya
kopplingen. Användarens installation och fysiska test av dessa versioner är
**ännu inte bekräftade**.

Senaste användarfelet var att enkla på/av-kommandon inte gjorde något och att
Vox-fjärren sade att Samsung-TV:n måste sparas i EutherBeam först, trots att
fristående Beams fjärr fungerade. Användaren rapporterade också att pucken nere
inte kunde väckas. Det är ännu oklart om fristående Beam kan väcka just den
pucken från standby, vilken modell den är och om nätverket är aktivt i standby.

**Rotorsak som hittades:** apparna hade separata enhetsinställningar och separata
Android Keystore-parningar. Vox kunde därför inte använda Beams befintliga
parningar. Dessutom använde röststyrningens puckväckning enbart Cast.

**Åtgärdat:** Vox delegerar nu fjärrkommandon till installerade Beam genom en
lokal signaturskyddad ContentProvider. Nycklar exporteras inte. Puckväckning
försöker parad Android Remote WAKEUP före Cast-reservvägen. På/av-kommandon får
röståterkoppling från det faktiska mobilresultatet.

## Användarens avsikt / avgränsning

- Endast TV-korten ska grupperas i **Uppe** och **Nere**. Andra apparater behöver
  inte klassas om.
- **Uppe = Samsung**, **Nere = NEC**. Varje kort kopplar rätt skärm till exakt
  rätt Android-puck och befintlig IR-volymdestination.
- Volym ska vara vanliga −/+ under fjärrkontrollen, utan IR-/Logitech-teknik i
  den vanliga användarvyn.
- Valt TV-rum i Vox används för kommandon utan uttrycklig destination. En
  uttrycklig destination i talet ska vinna för det yttrandet.
- Användaren vill kunna söka efter film, exempelvis ”jag vill titta på Terminator
  på NEC tvn”, och läsa vad pucken spelar om metadata finns.
- Commit, push och publicering i befintliga Apps har varit uttryckligen önskat.
- Inga fysiska apparater styrdes i senaste automatiserade testerna.

## Repon, commits och drift

### EutherVox

- Checkout: `/home/nichlas/EutherVox`
- Branch: `feature/tv-apparater`
- Remote: `https://github.com/NichlasEk/EutherVox.git`
- `32b60df`: beta 6, rumskort, filmsökning/status.
- `0b96cee`: beta 7, använd Beams parningar, på/av-återkoppling och puckväckning.
- `d45c96f`: inställningsvyn öppnar installerade Beams inställningar när bryggan
  finns, i stället för att uppmana till separat omparning i Vox.
- Ovanstående commits är pushade. Arbetskatalogen var ren före denna handoff.
- Gateway kör **lokalt på denna dator**, inte i en servercheckout av Vox:
  `systemctl --user status euthervox-gateway.service`, port **8788**.
- Gatewayen startades om efter senaste Python-ändringarna och var `active` när
  denna handoff skrevs. Kontrollera igen efter omstart.

### EutherBeam

- Checkout: `/home/nichlas/EutherBeam`
- Branch: `main`
- Remote: `https://github.com/NichlasEk/EutherBeam.git`
- `d586d18`: alpha 17, signaturskyddad delad fjärr och Android-väckning.
- Commit är pushad. Kontrollera live-status före nya ändringar.
- Delad Kotlin-kod finns både i Vox `beam/src` och Beam `app/src`. Ändringar i
  gemensam funktion måste speglas avsiktligt. Aktivitets-/temaingångarna skiljer
  sig; kopiera inte hela kataloger eller kör gamla synkskript blint.

### EutherOxide / Apps

- Server: `nichlas@192.168.32.186`
- **Servercheckout är källa till sanning:** `/home/nichlas/EutherOxide`.
- SSH-nyckel: `/home/nichlas/.ssh/euther_server`; nyckelinloggning, inte
  lösenordsinloggning. Spara inte lösenfrasen i handoff, Git eller loggar.
- Branch: `agent/euthernet-admin-boundary`
- Remote: `https://github.com/NichlasEk/EutherOxide`
- `674c18f`: föregående beta 6 / alpha 16-publicering.
- `c62eeec`: beta 7 / alpha 17-publicering, pushad.
- Uppdaterade `src/main.rs` nedladdningsrutter/defaults och `webview/main.ts`
  Apps-kort. Föregående versioners rutter är kvar.
- `npm run build` och `cargo build --release` lyckades på servern. Rustbygget
  tog cirka 4 minuter 51 sekunder. `sudo systemctl restart eutherhost.service`
  gjordes därefter och tjänsten var `active`.
- Serverns enda kvarvarande dirty fil var genererade `webview/build-info.ts`.
  Den var dirty redan före jobbet. Återställ inte okända ändringar.
- Serverns GitHub-HTTPS-autentisering fungerade inte. Push gjordes i stället
  genom att hämta servercommiten till lokal EutherOxide och pusha exakt den
  commiten med lokal GitHub-autentisering. Ingen lokal checkout synkades över
  servern. Lokal EutherOxide hade orelaterade ändringar i
  `scripts/eutherhost-backup-status.py` och `scripts/eutherhost-users-mirror.sh`;
  de lämnades orörda.
- Tillfällig SSH-agent stoppades efter publicering. Starta/ladda ny vid behov.

## Publicerade APK-filer och verifiering

### Vox beta 7

- Lokal: `build/releases/EutherVox-0.20.0-beta7-debug.apk`
- Server: `/home/nichlas/EutherVox-0.20.0-beta7-debug.apk`
- Senaste-länk: `https://apothictech.se/downloads/EutherVox.apk`
- Versionslänk: `https://apothictech.se/downloads/EutherVox-0.20.0-beta7-debug.apk`
- SHA-256:
  `0508551c0b39eaeabf8e731679c676f56ec14aa79bbe3dde7945f2d3cd9956ef`
- Lokala och serverlagrade bytes matchade. Vox kräver inloggning; anonym
  nedladdning gav förväntat HTTP 401. **Autentiserad publik nedladdning av beta 7
  verifierades inte i denna körning.**

### Beam alpha 17

- Lokal: `/home/nichlas/EutherBeam/build/releases/EutherBeam-0.1.0-alpha17-debug.apk`
- Server: `/home/nichlas/EutherBeam-0.1.0-alpha17-debug.apk`
- URL: `https://apothictech.se/downloads/EutherBeam-0.1.0-alpha17-debug.apk`
- SHA-256:
  `48128cb2181e19281207fbce407a52b6bf0ff926d671cc2ac77fa6c82f55d495`
- Publik nedladdning lyckades och hela filens SHA-256 matchade.

Apps-bundlen kontrollerades innehålla båda nya versionsetiketterna. APK:erna
installerades i emulator, inte på användarens telefon. Installera uppdateringar
over befintliga appar; rensa inte data/parningar.

## Viktiga kodställen / beteende

### Gemensam fjärr

- `beam/src/main/java/se/euther/eutherbeam/BeamLink.kt` i Vox, motsvarande i Beam.
- Provider finns **bara i fristående Beam**:
  `app/src/main/java/se/euther/eutherbeam/BeamControlProvider.kt`.
- Authority: `se.euther.eutherbeam.control`.
- Permission: `se.euther.eutherbeam.permission.CONTROL`, protectionLevel signature.
- Klienten kontrollerar rätt providerpaket och matchande app-signatur. Providern
  kontrollerar uttryckligen anroparens permission även i `call`.
- Metoder: `control`, `search`, `status`, `players`, `bind`.
- Bara befintliga allowlistade mål/kommandon. Inga godtyckliga IP-adresser från
  anroparen. Söktext/PCM är begränsade. Privata parningsnycklar stannar i Beam.
- `BeamVoice.kt` delegerar när bryggan finns. Utan kompatibel installerad Beam
  används Vox-modulens lokala konfiguration som tidigare.
- `RoomRemoteCards.kt` läser och sparar puckkopplingar genom Beam när den finns;
  laddar om vid återgång till appen. Visar ”Använder enheter och parningar från
  EutherBeam”. Ingen puck flyttas från andra TV-kortet utan att kopplas loss.
- Embedded `EutherBeamPanel` öppnar Beams inställningar när bryggan finns.

### Tal och återkoppling

- `server/gateway/beam.py`: deterministisk kommandotolkning, rum och alias.
- `server/gateway/session.py`: rum per session/yttrande, kortlivat väntande
  klargörande och åtgärdsresultat.
- Fraser: ”sätt på TV”, ”stäng av tv:n”, ”starta televisionen”, artiga
  infinitiver. NEC-TV och Samsung-TV normaliseras.
- Utan valt rum frågar ett generiskt TV-kommando Uppe/Nere. En avgränsad
  destination som ”NEC-TV” kan besvara frågan inom 45 sekunder. Väntande kommando
  rensas vid byte av rum eller nästa orelaterade yttrande.
- Nya klienter annonserar `tv_control_result_v1`. `VoiceController.kt` behåller
  aktivt yttrande för `beam.control`, liksom search/status. Gatewayen väntar på
  mobilens resultat och talar resultat/fel; vid 25 sekunders timeout sker ingen
  automatisk omsändning.
- Taligenkänningen i användarens logg hörde ”Sätt på TV” korrekt. Problemet var
  alltså inte bara STT. Gör inte om det till ett antaget taligenkänningsfel.

### Puckväckning

- `androidtv/PlayerWake.kt`: parad Remote WAKEUP först, Cast som reserv vid fel;
  oparad puck försöker Cast. Cancellation utlöser ingen reservsändning.
- WAKEUP = keycode 224, SLEEP = 223. Använd inte POWER-toggle som blind retry.
- TV-korten har separata **TV på/TV av** och **Väck puck/Vila puck**.
- Display-på/av betyder inte automatiskt att hela TV+puck-anläggningen startas.
- Cast-reservvägen är befintlig YouTube-LAUNCH för CEC-väckning, inte en
  verifierad avläsning av att hårdvaran faktiskt vaknade.
- Lyckad transport beskrivs som skickat kommando. Om båda vägarna misslyckas
  visas ett konkret nätverks-/standbymeddelande.
- Puck med avstängt nätverk i standby kan behöva separat IR/CEC/hårdvarulösning;
  exakt modell och beteende måste först fastställas. Ingen sådan lösning har
  verifierats eller lagts in för den riktiga pucken nere.

### Filmsökning från beta 6

- `server/gateway/tv_media.py`, `AndroidTvRemoteClient.kt`, `CastPlaybackClient.kt`.
- Filmtitel/valfri tjänst blir en begränsad syntetisk sökfras som skickas via
  Android Remote voice-protokollet. Detta är **sökning**, inte garanterad direkt
  uppspelning eller katalogkontroll hos streamingtjänsten.
- ”Vad visas?” använder read-only Cast GET_STATUS och kan falla tillbaka till
  aktuell Android-app. Saknad titel får inte uppfinnas.
- Ingen automatisk HDMI-gissning eller automatisk TV-start vid filmsökning.

## Utförda tester

- **131 Python-tester** passerade för room_routing, tv_media, volume_voice,
  remotes och session.
- Vox JVM: **57 passerade**.
- Inbäddad Beam JVM: **32 passerade, 5 opt-in hårdvarutester överhoppade**.
- Fristående Beam JVM: **32 passerade, 5 opt-in hårdvarutester överhoppade**.
- Båda APK-byggen och lint lyckades. Java 17 användes.
- Tvåappars-emulatortest: Vox egna `eutherbeam.xml` togs bort endast i
  emulatorns testinstallation. Bara fristående Beam hade den parade simulerade
  Nere-pucken. Vox röststyrning och rumskort nådde samma puck genom providern.
- Simulatorn såg WAKEUP 224 och SLEEP 223. Vox fick rätt action.result.
- Vox-kortet visade Beams ”Testpuck Nere · Parad”. Ingen egen Beam-pref-fil
  återskapades i Vox av dessa fjärrkommandon.
- Filmsökning genom providern skickade SEARCH 84, voice session 77 och PCM-
  chunkar 20480/11520 byte. Status gav simulerad Terminator/Netflix/PLAYING.
- Obehörig shell-åtkomst till providern nekades med SecurityException.
- Ingen AndroidRuntime-crash syntes vid kontroll.
- **Detta bevisar inte fysisk TV/puck-väckning eller verklig streamingtjänstsökning.**

Artefakter (ignorerade i Git): `build/reports/tv-power/` med power-events.jsonl,
power-shared.png och shared-media-events.jsonl. Tidigare mediaartefakter finns i
`build/reports/tv-media/`. Tillfälliga simulatorer låg i `/tmp/vox-tv-probe/`;
/tmp är inte en beständig källa efter omstart. Emulator och mockserver stoppades
vid avslut. Kör inte mockskript mot riktiga apparater.

## Nästa steg efter omstart

1. Läs Git-status i berörda repon och kontrollera lokal gatewayservice. Lita
   inte på att processstatus i denna fil gäller efter omstart.
2. Invänta användarens test av **båda** nya APK:erna. Kontrollera versionsnamn
   och att Vox visar ”Använder enheter och parningar från EutherBeam”. Behåll
   existerande appdata och Beams parningar.
3. På TV-korten: kontrollera att rätt puck är kopplad till rätt skärm. Välj
   TV-rum Nere och testa var för sig ”sätt på TV”, ”stäng av TV”, ”sätt på pucken”
   samt Väck puck/Vila puck. Notera faktiskt felmeddelande och fysisk effekt.
4. Om pucken nere fortfarande inte vaknar: fråga modell, standby kontra full
   avstängning och om dess originalfjärr samt fristående Beam kan väcka den.
   Undersök nätverk/Remote/Cast i det faktiska viloläget; gissa inte IP/enhet från
   en öppen port. Skilj kommando mottaget från verklig väckning.
5. Felsökning av tal: använd snävt journalutdrag, exempelvis
   `journalctl --user -u euthervox-gateway.service --since '10 minutes ago' --no-pager`
   filtrerat på `stt_final|action_result|assistant_final`. Undvik att dumpa
   användartoken, privata nycklar eller stora orelaterade loggar.
6. Vid ny release: bygg/testa lokalt, arbeta i serverns EutherOxide-checkout,
   bevara äldre nedladdningar och lokala ändringar, bygg frontend/backend,
   starta om systemtjänsten och hashverifiera publicerade bytes.

Använd Firefox om autentiserad webbkontroll behövs. Starta inte Chrome.
Ingen minneskatalog har ändrats; denna fil är en projekthandoff.
