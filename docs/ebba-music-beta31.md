# Ebba som jukebox — Vox beta 31

## Prova hemma

Installera EutherVox 0.19.0-beta.31 (55) från EutherOxide → Apps.
Öppna Robotdammsugare → Ebba som jukebox → Öppna musiken.
Skriv låt och artist, eller klistra in en YouTube/YouTube Music-länk till en låt.
Tryck Spela på Ebba. Sökning använder det kopplade YouTube-kontot och spelar
första träffen; en direkt låtlänk behöver inte sökfunktionen.

- Jukebox: låt kryssrutan vara av, Ebba måste stå still.
- Städmusik: starta gärna städningen först och låt hennes startmeddelande bli
  klart. Kryssa Tillåt musik under städning och starta sedan låten.
- Kryssrutan gäller när en ny låt startas. Den startar aldrig robotrörelse.
- Pausa/Fortsätt behåller ungefärlig låtposition. Stoppa musik stänger endast
  ljudet. Vanliga dammsugarkontroller används för städning och hemgång.
- Musikvolymen är mjukvaruvolym, den ändrar inte robotens talvolym.
- Musiken fortsätter utan öppen telefonapp. En låt per start, max 20 minuter.
- I städläget stängs musiken efter att tjänsten har sett städning följt av laddning.
- Robotens egna ljud, pratläget och budbäraren pausar musiken. Återuppta själv
  med Fortsätt när talet är klart. Ingen automatisk återstart efter robotfel.
- Detta är en egen Vox-musikdestination, inte en Google Cast-mottagare.

## Ljudväg och gränser

Autentiserad Vox-WebSocket → serverns YouTubeAudioResolver → fast, autentiserad
EutherWash-API. Endast HTTPS-ljudkällor under googlevideo.com accepteras, inga
klientvalda SSH-kommandon eller lokala filvägar. ffmpeg på EutherOxide-servern
avkodar till mono PCM 16 kHz. Ljudet strömmas i små paket genom SSH till aplay.
Ingen låt sparas på robotens flash eller på serverns disk. Ursprunglig
hårdvaruvolym lämnas orörd. Mobilen behöver inte skicka musikpaketen.

Ljudet är begränsat av robotens talhögtalare och 16 kHz/mono. Inget hifi-löfte.
Separat statusvakt kontrollerar driftläge/fel var tredje sekund. Strömmen har
20 minuters gräns och en oberoende 1205-sekunders timeout på roboten. Nätfel,
ljudfel och bruten process stänger transporten. Nytt ljud kräver uttryckligt val.

## Företräde för robotens egna meddelanden

Originalskript /ava/script/mediad_script.sh SHA-256:
`c1927ced8dc3b579d8305a27552c1931e47896ec70cff05a1ce019e599c9c2a8`.
Vid musikstart kontrolleras skriptet. En kopia med en kort prelude bind-monteras
från /tmp/euther-music-mediad.sh. Den stänger endast aplay vars process-id och
kommandorad matchar /tmp/euther-music-<uuid>.pid, innan originalets ogg123 startar.
Ingen flashändring; robotomstart tar bort bind-mounten och nästa musikstart
återställer den efter kontroll. Okänd skriptversion avvisas.

Original/kopia finns lokalt i .private/music-beta31/. Återställ medan musiken är
stoppad med `umount /ava/script/mediad_script.sh` eller starta om roboten.
Serverbackup före ändringen: ~/.local/state/eutherwash/private/before-music-beta31/.
EutherOxide: .euther-host/backups/euthervox-beta31-20260916/.

## Verifiering

Android debugbygge och JVM-tester passerade. Backendtester täcker URL-gränser,
volym, driftläge, robotfel, paus/stopp, prioritetsskriptets versionskontroll,
städning→dockning och autentisering. Gatewaytester täcker obehörig åtkomst,
ogiltiga länkar och extractor-fel utan att stänga Vox-sessionen.

Verkligt tyst test: en YouTube-ström avkodades på servern och skickades till
Ebbas högtalare med musikvolym 0. Spelande status, paus med bevarad position,
fortsättning, volymkontroll och stopp fungerade. Ebba förblev dockad.
Hörbar kvalitet samt faktisk samtidig städning återstår för användarens hemtest.

## Publicering och slutkontroll

Beta 31 versionCode 55. APK SHA-256:
`245a47b284908e5214a5729c0ce0bd43f16a471b404c9a7e79d45235e8905a00`.
Samma debugsignatur som tidigare version. EutherOxide releasebygge klart och
hosttjänst omstartad. Vox-gateway och EutherWash har startats om med nya koden.

Tyst liveprov av prioritet: musik → PTT (musiken pausas) → musik fortsätter →
robotens prioritetsprelude körs utan tal → musiken pausas → explicit stopp fungerar.
Redan avslutad aplay-process med kvarvarande pidfil räknas korrekt som avslutad;
regressionstest täcker det. Ingen städning eller robotrörelse startades.

Efter sista driftsättningen: musik stoppad, standardvolym 50 %, Ebba online,
laddar och felkod 0. Gateway/Cast-tester: 87 passerade. Androidbygge/JVM gröna.
Musik-, intercom- och leveranstester: 37 passerade efter sista stoppfixen.

## Länkfix 2026-09-16

YouTube Music-länken med video-id ouQmlz3I3v8 (Dinosauriernas alfabet) gav
"This video is not available" eftersom yt-dlp-ejs saknades. Deno fanns redan.
Installerade yt-dlp-ejs 0.8.0 som matchar yt-dlp 2026.07.04, ändrade projektets
beroende till yt-dlp[default] och uppdaterade uv.lock för reproducerbar installation.
Verifierat samma ID genom resolver samt ffmpeg på 192.168.32.186: exit 0,
64000 PCM-byte från två sekunder ljud. Ingen robotuppspelning startades.
Gateway omstartad; beta31-appen behöver inte uppdateras.
Referens: https://github.com/yt-dlp/yt-dlp/wiki/EJS
