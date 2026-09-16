# Säg med Ebbas röst här — beta 32

Vox 0.19.0-beta.32, versionCode 56. Under Budbärare finns nu knappen
**Säg med Ebbas röst här** direkt under meddelandets textruta.

Skriv text och tryck. Ingen karta, målpunkt eller leveransbekräftelse krävs.
Robotens aktuella driftläge måste vara stillastående (idle/paused/charging),
anslutet och felfritt. Det fungerar därför även i dockan. Samma syntetiska
Ebba-röst, textgräns 400 tecken och ljudgräns 30 sekunder används.
Musik pausas innan uppdraget startar; pågående mikrofonström stängs.

Den nya funktionen är separat från Håll inne för att prata genom Ebba, som
fortfarande strömmar telefonens mikrofon. Skicka Ebba fungerar som tidigare.

## Teknisk avgränsning

Autentiserad delivery/speak-rutt, strikt SpeakRequest med UUID och text.
Samma single-flight-lås och idempotenta uppdragskatalog används som för
budbäraren. Uppdragets kind=speech döljer Avbryt och åk hem i UI.
Ny speech_worker skapar TTS, kontrollerar stillastående igen och spelar ljudet.
Den anropar inga rörelsekommandon, läser ingen karta och har ingen hemgång.
Generering kan avbrytas; redan startad uppspelning kan ta upp till 30 sekunder
att avsluta via den befintliga ljudtransporten. Ljudfiler rensas efter jobbet.
Generering väntar max 210 sekunder och systemd-jobbet har 300 sekunders gräns.

## Verifiering

Tester täcker idle/paused/charging, avvisad cleaning, avbrott före syntes,
dubblettskydd och single-flight utan karta. Testets robotobjekt har endast
status/uppläsning, inga navigationsmetoder. Gatewaytest kontrollerar POST-rutt,
autentisering och payload utan kartfält. Live-rutten avvisar blank text korrekt.
Ingen ny robotrörelse eller hörbar uppläsning har startats vid detta införande.
Hörselprov görs via den nya knappen av användaren.

Backendbackup: ~/.local/state/eutherwash/private/before-direct-speech-beta32/.
EutherOxidebackup: .euther-host/backups/euthervox-beta32-20260916/.

APK SHA-256: `05f0431379cfe6410d0781eba3043ff6c110898afcb3a35857facf65426e5e10`.
Androidbygge/JVM-tester gröna. Leverans + direktuppläsning: 19 tester passerar.
Gateway/EutherWash-sessionstester: 73 passerade; efter nytt ruttest passerar
alla 14 EutherWash-gatewaytester. Debugsignaturen är verifierad.
