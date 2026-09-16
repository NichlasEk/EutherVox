# Ebba: håll inne och prata — Vox beta.30

## Testa

Uppdatera från EutherOxide → Apps till **0.19.0-beta.30 (54)**.
I Budbärare: välj mål, kryssa **Stanna här efter leveransen** och skicka.
Texten är valfri i detta läge. Med text spelas först Ebbas syntetiska meddelande;
utan text kör hon direkt till platsen och stannar.

Håll inne **Prata genom Ebba**. Vänta tills **Prata nu** visas och prata i mobilen.
Släpp för att stänga mikrofonen/strömmen. **Kör hem** är en separat knapp som
stänger talet före rörelsekommandot. Det går även att prova talet när Ebba redan
står still eller är dockad, utan en leverans. Om Android ber om mikrofontillstånd:
tillåt och håll sedan inne igen.

Utan kryssrutan behålls tidigare beteende: leverera och återvänd automatiskt.
Detta är envägstal; ingen mikrofon i Ebba används.

## Flöde

Mobilens PCM 16 kHz/mono/S16LE skickas i 200 ms-paket via autentiserad Vox-WSS,
vidare till EutherWash på LAN och genom en bestående SSH-kanal till `aplay`.
Ingen STT eller TTS används för mikrofonljudet och inga ljudfiler skapas.
Gateway skickar kvittens per paket. Mobilen har högst en väntande buffert utöver
paketet som kvitteras; för lång kö stänger strömmen i stället för att spela sent.

- Inspelning startar först efter serverns klartecken och endast medan fingret
  fortfarande hålls nere och appen är i fokus.
- Släpp, lämna panelen, bakgrund eller tappad Vox-anslutning stänger mikrofonen.
- Varje tryck får eget UUID och stigande paketnummer. Försenade paket/släpp från
  ett gammalt tryck kan inte påverka nästa tryck.
- Bara en telefon kan använda högtalaren åt gången.
- Färskt stillastående/felfritt driftläge krävs. Under tal används en liten
  live-statusbatch högst en gång per sekund, utan kart- eller serviceavläsningar.
- Servern avbryter efter 1,5 s utan paket, vid robotfel/rörelse eller försenat ljud.
  En pågående nätverksavläsning kan lägga till sin begränsade timeout.
- Appen stoppar efter 28 s per tryck. Servern har 30 s-gräns och roboten kör
  dessutom `timeout -s KILL 35 aplay`. Släpp och håll inne igen för mer tal.
- Explicit stopp dödar just den aktuella aplay-processen via dess unika pidfil,
  med kontroll av processens kommandorad, så köat ljud kastas.
- Vanliga rörelsekommandon stänger strömmen först. Om fjärrstoppet inte kan
  bekräftas blockeras ny rörelse/tal fram till robotens hårda ljuddeadline.
- WAN-blockeringen på Ebba förblir aktiv. Telefonen använder vanliga Vox-rutten.

Stanna-kvar-jobbet avslutas som `parked` efter bekräftat stopp och eventuell
uppspelning. Ingen navigeringsworker ligger kvar och väntar på telefonen, och
ingen automatisk hemgång görs för att telefonen försvinner. Robotens egna
funktioner, t.ex. låg batterinivå, ändras inte. Nästa vanliga robotkommando
avslutar den sparade budbärarstatusen.

## Kod och drift

EutherWash: `intercom.py`, `delivery.py`, `delivery_worker.py`, liten
`LocalVacuumReader.read_motion_status()`, autentiserad
`POST /v1/vacuums/{alias}/delivery/talk/{start,frame,stop}`.
Samma IP-/tokenkontroll som övrig budbärarstyrning. Inga nya portar.

Vox gateway: sessionbunden `vacuum.talk`, rensning vid frånkoppling.
Android: separat PcmMicrophoneSource, kvitterad sändare, mikrofonsamtycke,
tryck/släpp och livscykelhantering i DeliveryPanel/VoiceController.

Backup på servern:
`~/.local/state/eutherwash/private/before-talk-beta30/eutherwash/`.
Oxide: `.euther-host/backups/euthervox-beta30-20260916/`.
Konfiguration och SSH-hemligheter återanvänder beta.28:s privata inställningar.

## Verifiering och återstående hörselprov

- Backendtester täcker parkering med/utan text, ingen hemgång i stanna-läge,
  exklusiv högtalare, ordningsföljd, felaktiga paket, gammalt UUID, köförsening,
  robotrörelse, tappade paket, hård tidsgräns och stopp före hemkommando.
- Gatewaytester täcker autentisering, sessionens ljudrutt och avstängning när
  anslutningen försvinner.
- Androidbygge och befintliga JVM-tester passerar.
- Livetest med tyst PCM: högtalaren öppnades på 0,92 s, sex paket passerade,
  stopp accepterades. Inga rörelsekommandon skickades.
- Ytterligare tyst livetest: andra avsändaren avvisades, uteblivna paket stängde
  strömmen, nästa tryck fungerade och ett gammalt släpp påverkade inte det nya.
- Testerna verifierar verklig ljudtransport, inte upplevd talstyrka eller
  mobilens totala mikrofon-till-högtalare-latens. Det hörselprovet görs nu i Vox.

## Publicerad version

- Beta 30, versionCode 54, publicerad under EutherOxide Apps.
- APK SHA-256: `54ae5aadfa4b00a628174cc7b0ca96f379a50ad6a96029416ac85b43ef3043c9`.
- Autentiserad standardnedladdning och beta30-adress gav HTTP 200, korrekt APK-MIME och samma hash. Beta29-adressen fungerar fortfarande.
- EutherWash: 149 tester passerade. Gateway omstartad och aktiv.
- Efter driftsättning lyckades nytt tyst livetest: Ebba laddade utan fel; start, sex PCM-paket och stopp accepterades. Start tog 2,7 sekunder vid detta prov.

## Möjlig fortsättning: musik

Vox har redan `YouTubeAudioResolver` samt Cast-styrning. En egen musikdestination Ebba kan byggas genom serveravkodning och separat längre ljudström till roboten. Detta är ännu inte implementerat. Native mottagning direkt från YouTube Musics Cast-lista är inte verifierad; Googles receiver-SDK förutsätter Cast-kompatibel mottagarplattform.
