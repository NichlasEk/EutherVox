# Ebba som budbärare — Vox beta.28

## Testa i appen

Installera EutherVox **0.19.0-beta.28 (52)** från EutherOxide → Apps.
Logga in och anslut som vanligt. Under Robotdammsugare finns **Ebba som budbärare**.
Öppna panelen, hämta aktuell karta, nypzooma/dra och tryck på fri golvyta.
Skriv ett kort meddelande och välj **Skicka Ebba → Ja, skicka**.
Börja med samma korta sträcka som redan provats, ungefär 2,5 meter från dockan.

Ebba ska vara dockad, felfri, utan aktiv uppgift och ha minst 30 % batteri.
Första versionen begränsar målet till fem meter från startpositionen.
Transporten använder firmwareläget för punktstädning: borsten kan gå på vägen.
Hon stoppas innan talet spelas och återvänder först när uppspelningen avslutats.
Knapp för avbrott med respektive utan hemgång finns under uppdragsstatus.
Avbrott behandlas mellan de begränsade nätverksoperationerna; en pågående
ljudfras kan spelas färdigt medan roboten står still.

Detta är första appintegrationen. Den tidigare fysiska provkörningen lyckades,
men hela nya appflödet behöver fortfarande provas av användaren.
Kartmarkering är inte en garanti för centimeterprecision eller fri väg i verkligheten.
Den inbyggda robotnavigeringen och hinderhanteringen används fortfarande.

## Arkitektur och drift

- Android: `EutherVox/.../DeliveryPanel.kt`, protocol + VoiceController.
- Gateway på arbetsstationen: autentiserad `vacuum.delivery` WebSocket-åtgärd.
- EutherWash på 192.168.32.186: `/v1/vacuums/{alias}/delivery`, `/map`, `/cancel`.
  Även läsning kräver ordinarie kontrolltoken och tillåten klient-IP.
- Varje accepterat uppdrag körs i egen `systemd --user`-enhet:
  `ebba-delivery-<UUID utan bindestreck>`. Appen och API-processen äger inte rörelsen.
- Ett aktivt uppdrag åt gången, filbaserad idempotens och låsning även över
  API-omstarter. Ordinarie start/kartläggning blockeras under leverans;
  ordinarie stopp/paus/hemgång avbryter leveransen.
- Robotkartan hämtas lokalt via SSH från aktivt `fine.bin`, inte gammalt kartarkiv.
  SHA256, kartidentitet och firmwarehash kontrolleras. Mål måste ha minst 22 cm
  marginal i den sparade kartans golvklassning. Detta är en heuristik, inte en
  ersättning för robotens sensorer eller bevisad fri passage.
- Read-only positionsavläsning från den verifierade R2205-processen.
  Två färska prover inom 35 cm begär stopp. Bromsning och avläsningslatens ger
  ytterligare avvikelse. Kart-/bootbyte eller utebliven färsk position avbryter.
- Separat stoppvakt efter 360 sekunder; systemd hårdgräns 400 sekunder.
  Utkörning högst cirka 100 s, hemgång 75 s, ljud högst 30 s.
  Förlorad kontakt kan inte garantera att ett stoppkommando når roboten.
- TTS görs parallellt med utkörningen via EutherLink på 192.168.32.88:8765,
  `dots.tts-mf`, samma privata syntetiska Ebba-referens och parametrar som
  installerat röstpaket. Datorn med EutherLink måste vara igång.
- PCM S16_LE/mono/16 kHz, normaliserat till -20 LUFS, skickas genom SSH till
  `aplay`. Endast explicit upptagen ljudenhet försöks igen (max tre försök),
  med ny kontroll att roboten står still före varje försök.
- Ingen mikrofon eller push-to-talk är implementerad i detta steg.

Privat konfiguration:
`/home/nichlas/.config/eutherwash/delivery.json`
aktiveras med `EUTHERWASH_DELIVERY_CONFIG` i
`~/.config/systemd/user/eutherwash.service.d/delivery.conf`.
Nycklar: `host`, `password_file`, `known_hosts`, `directory`, `reference_file`, `tts_url`.
Befintliga SSH-lösenords-/known-hosts-filer återanvänds. Hemligheter ligger utanför Git.

Jobb/status/cancel/eventloggar ligger privat under
`~/.local/state/eutherwash/private/deliveries/<UUID>/` på servern.
Ljudfiler tas bort efter jobbet; texten finns kvar i privata request-/TTS-loggar
för felsökning. EutherLinks befintliga jobb/lagringspolicy gäller dess TTS-kopia.
Katalogen innehåller även privata kartcachefiler. Lägg aldrig till den i Git.

## Verifierat i detta steg

- EutherWash hela befintliga testsamling passerade efter första kopplingen.
- Nya tester: auth/extra-fält/bekräftelse, enkel aktiv leverans/idempotens,
  målgränser, lyckad ordning stopp→tal→hem, gammal position, TTS-fel och avbrott.
- Gatewaytester för fasta rutter, auth, felöversättning och sessionkrav.
- Android assembleDebug lyckades, rätt paket/version och befintlig debugsignatur.
- Live gateway→server: karta 10, 475×406, 6164 rader; aktuell jobbstatus svarar.
- Riktig systemd-worker med avbrott redan på disk avslutades `cancelled` utan
  rörelse eller uppspelning. Detta verifierar arbetarmiljön, inte ny fysisk körning.
- Servergenererat test med Ebba-referensen: 3,36 s tal, 5,32 s syntes,
  7,29 s inklusive konvertering. Ingen uppspelning på roboten i detta test.

Serverbackup av tidigare EutherWash-kod:
`~/.local/state/eutherwash/private/before-delivery-beta28/eutherwash/`.
Oxidebackup: `.euther-host/backups/euthervox-beta28-20260916/`.
Vid återställning: inaktivera delivery-drop-in, återställ backendfiler och
starta om API:t. Stoppa/bekräfta ett aktivt uppdrag först; en API-omstart
avslutar avsiktligt inte dess självständiga worker.

## Slutlig publicering

- 134 EutherWash-tester och 70 gateway-/sessionstester passerar.
- Budbärarens statuspollning körs endast när panelen är öppen och appen i fokus.
- APK SHA256: `492e735f08e6703f4d1a70af2fde32f23d982830ef18b1ec6b4d9c13420e722b`.
- Beta.27 och beta.28 har samma verifierade APK-signatur.
- Inloggad nedladdning via standardlänken och beta28-länken ger HTTP 200,
  APK-MIME och rätt SHA256. Utan inloggning krävs autentisering som tidigare.
