# EutherVox 0.17 beta

EutherVox är en lokal, strömmande röstprototyp. Android-telefonen står för mikrofon, högtalare och UI; gatewayen tar emot rå PCM över WebSocket och kör en utbytbar STT → figur → textgenerator → TTS-kedja.

Android-appen har en lokal `Ljus`-flik för Magic Home Wi-Fi-moduler. Den söker
kontrollers på LAN, läser status och kan tända, släcka samt prova grundfärger.
Fliken innehåller också en guide som ansluter en ny modul till ett 2,4 GHz-nät
utan att skicka eller spara Wi-Fi-lösenordet på EutherVox-servern. Det äldre
BLE-laboratoriet finns kvar som en avancerad reserv. Se
`docs/magic-home-wifi.md`.

Upptäckta Wi-Fi-ljus kan namnges och knytas till rum i appen. Registret sparas
atomiskt som server-TOML och används av samma allowlistade verktyg i Ollamas
lokala tool calling och EutherVox MCP. Appen har en kompakt kulör-/mättnadsruta,
lodrät ljusstyrka och verifierade Magic Home-mönster. Färgblinkning tidsstyrs
av appen över en beständig lokal TCP-anslutning eftersom vissa AK001-firmware
ignorerar protokollets hastighetsbyte.

`Musikljus` använder telefonens mikrofon som lokal analyskälla. Användaren kan
välja ett eller flera upptäckta ljus, basfärg och känslighet. Appen extraherar
endast ett adaptivt nivåvärde ur 50–145 Hz-bandet och skickar synkroniserade
färgkommandon ungefär 20 gånger per sekund; rått ljud skickas, loggas eller
sparas aldrig. Push-to-talk och samtalsläge stoppar musikmikrofonen innan de tar
över AudioRecord. Första betan använder en telefon som dirigent; valbara
Raspberry Pi-rumsmikrofoner är en senare protokollutvidgning.

Den inbyggda mock-kedjan kräver inga AI-modeller. Den transkriberar till `Var ligger min lödkolv?`, svarar som Skinnskattaren och strömmar en kort testton som TTS-ljud. Tonen gör att hela ljudvägen kan verifieras, men är inte syntetiserat tal.

Det finns även en riktig svensk betaprofil: flerspråkig faster-whisper för STT, en liten svensk-capabel Qwen-modell via Ollama och Piper `sv_SE-nst-medium` för snabb CPU-TTS. Dots/VoxCPM är avsiktligt inte dialogstandard; de passar bättre som valbara kvalitetsmotorer för längre uppläsning.

Betaprofilen har fem valbara röster i Android-inställningarna:

- `NST – snabb`: svensk Piper-standard och automatisk fallback.
- `Lisa – alternativ`: den andra officiella svenska Piper-rösten.
- `MOSS Nano – experimentell`: MOSS-TTS-Nano-100M med strömmande ONNX-inferens på CPU.
- `Chatterbox – långsam`: Chatterbox Multilingual V3 via en isolerad lokal GPU-worker.
- `GrapheneOS Matcha – English`: snabb engelsk CPU-röst för Sherlock Holmes.

Skinnskattarens TOML-profil innehåller en uttalsordlista för namn och förkortningar.

## Snabbstart

Krav: Python 3.11+, Android SDK 36, JDK 17 och en Android-enhet med Android 8 eller senare.

Starta gatewayen på datorn:

```bash
cp config.example.toml config.toml
uv sync --extra dev
uv run euthervox-gateway --config config.toml
```

Alternativt, med redan installerade Python-beroenden:

```bash
PYTHONPATH=server python -m gateway.main --config config.toml
```

### Riktig svensk betaprofil

Installera den isolerade runtime-miljön och hämta den svenska rösten:

```bash
uv sync --extra dev --extra real --extra cuda
mkdir -p models/piper models/faster-whisper
uv run python -m piper.download_voices --download-dir models/piper sv_SE-nst-medium
uv run python -m piper.download_voices --download-dir models/piper sv_SE-lisa-medium
ollama pull qwen3:4b-instruct
```

Chatterbox installeras separat för att inte blanda dess PyTorch-beroenden med gatewayn:

```bash
cd chatterbox-worker
uv sync
HF_HOME=../models/chatterbox-cache .venv/bin/python worker.py
```

MOSS Nano installeras i en separat CPU-miljö och använder de officiella ONNX-vikterna:

```bash
uv sync --project moss-worker --python 3.12 \
  --extra-index-url https://download.pytorch.org/whl/cpu \
  --index-strategy unsafe-best-match
mkdir -p models/moss-tts-nano-onnx/MOSS-TTS-Nano-100M-ONNX
mkdir -p models/moss-tts-nano-onnx/MOSS-Audio-Tokenizer-Nano-ONNX
moss-worker/.venv/bin/hf download OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX \
  --local-dir models/moss-tts-nano-onnx/MOSS-TTS-Nano-100M-ONNX
moss-worker/.venv/bin/hf download OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX \
  --local-dir models/moss-tts-nano-onnx/MOSS-Audio-Tokenizer-Nano-ONNX
cp deploy/euthervox-moss.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now euthervox-moss.service
```

Sherlock Holmes använder den Matcha-röst som redan finns i GrapheneOS
Speech Services-checkouten. Workern håller ONNX-modellen varm och binder endast till
`127.0.0.1:8793`:

```bash
cp deploy/euthervox-matcha.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now euthervox-matcha.service
curl http://127.0.0.1:8793/health
```

Servicefilen pekar på de befintliga tillgångarna under
`/home/nichlas/SpeechServices/app/src/main/res/raw` och Matcha-renderaren i
EutherLink. Dessa modeller kopieras inte in i EutherVox-repot. Sherlocks profil
har `input = "auto"`, så samma flerspråkiga Whisper kan känna igen både svenska
och engelska. Hans `response = "en"` styr både modellprompten och TTS-anropet:
han svarar därför alltid på engelska oavsett vilket av de två språken du använder.

MOSS-workern binder endast till `127.0.0.1:8791`, skickar mono PCM medan ONNX-
avkodningen fortfarande arbetar och använder ingen GPU. Den lokala installationen
använder Sören Svartkruts rena, syntetiskt skapade svenska referensfil från
Stormakt3020. Kopiera den utan radiofiltret till den ignorerade modellkatalogen:

```bash
mkdir -p models/moss-reference/stormakt3020
cp ../WaylandForge/assets/stormakt3020/radio/references/soren-svartkrut-reference.wav \
  models/moss-reference/stormakt3020/soren-svartkrut-reference.wav
cp ../WaylandForge/assets/stormakt3020/radio/references/kung-christian-reference.wav \
  models/moss-reference/stormakt3020/kung-christian-reference.wav
```

Christian använder i den lokala betan i stället den lugna, svenska
grosshandlarreferensen `christian-grosshandlare-calm-b.wav`. Dess reproducerbara
VoxCPM2-beställning och hash finns i
`voice-references/christian-grosshandlare.json`. Krigskungsreferensen behålls
endast som historiskt A/B-underlag eftersom dess pressade leverans gav flåsig
dialog i MOSS.

Referensfilen anges i servicefilen och versionshanteras inte. Om den saknas
startar workern med en inbyggd röst. `GET /health` visar `reference_name`, så det
går att verifiera vilken profil som faktiskt laddades. Samma MOSS-process kan
ha flera namngivna, tillåtna referenser utan att ladda modellen flera gånger.
Gatewayen skickar `skinnskattaren` för Skinnskattaren och `christian` för den danske
grosshandlaren; godtyckliga filsökvägar accepteras aldrig över HTTP.

Skinnskattaren använder en lugn, rikssvensk VoxCPM2-referens i stället för
Sören Svartkruts skånska rövarröst. Beställning, seed, urval och hash finns i
`voice-references/skinnskattaren.json`. Den råa kandidaten valdes efter MOSS-
och Whisper-jämförelse; nivånormalisering försämrade begripligheten och används inte.
MOSS-profilerna har separata deterministiska samplingfrön, så ett tydligare
Skinnskattaren-frö inte ändrar Christians redan godkända leverans.

Chatterbox-workern binder endast till `127.0.0.1:8790`. Om kvalitetsrösten inte svarar innan
första ljudblocket går röstroutern automatiskt tillbaka till NST. Samma provtext
kan renderas med samtliga röster via:

```bash
uv run python scripts/compare_tts.py
```

Chatterbox är experimentell för dialog. På RTX 4090 tog ett varmt kort svar cirka
3,9 sekunder och den längre jämförelsefrasen cirka 8,8 sekunder innan första PCM-
blocket. Workern använde cirka 3,9 GB VRAM. Piper började däremot lämna ljud efter
ungefär 0,23–0,26 sekunder. Välj därför Chatterbox för röstkvalitetstest och NST
för det snabbaste samtalet.

På den lokala Xeon E5-2697 v3 gav MOSS Nano första router-ljudblocket efter cirka
0,54–1,03 sekunder. Ett varmt kort svar tog 4,05 sekunder att generera och gav
cirka 3,2 sekunder ljud. Det är mycket snabbare till första ljud än Chatterbox,
men något långsammare än realtid totalt på denna äldre CPU. NST förblir standard
tills MOSS har bedömts subjektivt på svenska.

Whisper-modellen laddas ner första gången profilen startas. Därefter väljer adaptern den lokala snapshotsökvägen direkt och gör ingen Hugging Face-kontroll vid normal start. Starta med:

```bash
uv run euthervox-gateway --config config.real-beta.example.toml
```

Standardprofilen använder flerspråkiga Whisper `small` på CUDA. Den gav både exakt svenska och 52 ms varm STT i det lokala syntetiska testet. CUDA 12-biblioteken installeras endast i projektets venv och laddas av adaptern; ingen systeminstallation krävs. Utan NVIDIA-GPU används `config.real-beta.cpu.example.toml`, som kör Whisper `base` med CPU int8.

För en CPU-only-installation utelämnas CUDA-extra:

```bash
uv sync --extra dev --extra real
uv run euthervox-gateway --config config.real-beta.cpu.example.toml
```

Båda profilerna använder normalt `language = "sv"` och `task = "transcribe"`, så Skinnskattaren och Christian förblir låsta till svenska och tal översätts inte. Sherlocks figurprofil gör ett avgränsat undantag med automatisk språkidentifiering för svenska eller engelska. Lägg bara till `hotwords` efter mätning; en bred ordlista visade sig kunna förvränga vanliga svenska fraser. Gatewayen värmer STT och Qwen före den börjar lyssna; Ollama håller sedan Qwen varm i 30 minuter för att undvika dess uppmätta kallstart på cirka 4,45 sekunder.

### Publik anslutning via EutherOxide

Betan kan använda `wss://apothictech.se/euthervox/ws`. Appen loggar då in via EutherOxides befintliga `/api/app/login`, sparar aldrig lösenordet och krypterar den långlivade app-token med Android Keystore. Caddy släpper endast igenom WebSocket-upgraderingar vars Bearer-token tillhör en aktiv, icke bannlyst EutherOxide-användare.

Gatewayen kan hållas igång på modellmaskinen med den versionshanterade user-servicen:

```bash
mkdir -p ~/.config/systemd/user
cp deploy/euthervox-gateway.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now euthervox-gateway.service
```

Bygg Android-appen:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

I appens inställningar anger du datorns LAN-adress, exempelvis `ws://192.168.1.20:8788`. Ingen serveradress ligger i källkoden. Telefon och dator måste kunna nå varandra i samma lokala nät. Öppna vid behov TCP-port 8788 i datorns lokala brandvägg.

## Testa den vertikala kedjan

1. Starta gatewayen och appen.
2. Spara serveradressen och vänta på `Ansluten`.
3. Ge mikrofonbehörighet.
4. Håll den stora knappen intryckt och tala i minst en halv sekund.
5. Släpp knappen. Kontrollera preliminär/slutlig text, Skinnskattarens svar och att testtonen börjar innan hela TTS-strömmen har kommit fram.
6. Tryck `Avbryt uppspelning` under tonen och kontrollera `response.cancel` i serverloggen.
7. Stoppa gatewayen och starta den igen för att kontrollera automatisk återanslutning.

## Naturligare samtalsläge

Version 0.9 behåller push-to-talk och lägger till ett uttryckligen aktiverat samtalsläge:

1. Tryck `Starta samtal` och börja tala.
2. Telefonen upptäcker tal och cirka 650 ms avslutande tystnad lokalt och skickar då `audio.end`.
3. Gatewayen behåller de senaste tio fråge-/svarsparen i WebSocket-sessionens RAM. De skickas till Qwen som riktig rollhistorik, men skrivs inte till disk.
4. När modellens första fullständiga mening är klar börjar Piper syntetisera den samtidigt som nästa mening genereras.
5. Efter avslutad uppspelning återgår appen automatiskt till lyssning.
6. Tryck `Avbryt och tala`, eller börja tala om telefonens Android-implementation erbjuder fungerande akustisk ekosläckning.
7. Tryck `Avsluta samtal` för att stoppa mikrofon, uppspelning och aktuell respons.

Om ingen börjar tala inom tio sekunder avslutas samtalsläget automatiskt. Vid app-paus, rotation, frånkoppling eller fel stängs AudioRecord och AudioTrack precis som i push-to-talk-läget. Automatiskt talavbrott använder `VOICE_COMMUNICATION`, Androids `AcousticEchoCanceler` och `NoiseSuppressor`; om ekosläckning saknas används den manuella avbrottsknappen. Mikrofonindikatorn är aktiv även när appen lyssnar lokalt efter ett avbrott, men dessa monitorblock skickas inte till servern.

## Struktur

```text
app/src/main/java/se/euther/euthervox/
├── MainActivity.kt                 Compose-UI och inställningar
├── app/VoiceController.kt         sessionslogik, status och latens
├── audio/AudioEngines.kt          AudioRecord/AudioTrack bakom gränssnitt
├── network/WebSocketClient.kt     WebSocket-transport och begränsad skrivkö
└── protocol/Protocol.kt           kontrollmeddelanden och parser

server/gateway/
├── main.py                        WebSocket-server
├── session.py                     sessionsmaskin och pipeline
├── adapters.py                    motorgränssnitt och mockar
└── config.py                      TOML-konfiguration
```

Android spelar inte in till fil och loggar inte rått ljud. Mikrofonen startas först efter push-to-talk eller ett uttryckligt `Starta samtal` och frigörs vid finger upp, `Avsluta samtal`, app-paus eller fel. En begränsad kö mellan AudioRecord och WebSocket gör att nätverksstopp inte blockerar ljudinläsningen; tappade block visas i UI.

## Spela musik på telefonen

Version 0.3 introducerar ett generellt, vitlistat åtgärdsprotokoll. Säg exempelvis:

```text
Spela Ghost på YouTube Music.
Spela upp något mörkt och lugnt på YouTube Music.
```

Gatewayen tolkar bara tydliga yttranden som börjar med `spela` eller `spela upp`. Den skickar `media.play` med en söksträng och telefonens eget nodnamn. Android kontrollerar åtgärden och startar sedan YouTube Music via plattformens `MEDIA_PLAY_FROM_SEARCH`. YouTube Music måste vara installerat och inloggat. Ingen Google-token eller YouTube-hemlighet lagras i EutherVox.

Detta är medvetet nodneutralt: en framtida rumsnod kan implementera samma `action.request` och `action.result` utan att känna till STT- eller figurmodellerna.

## Privata humörspellistor: lokalt original + YouTube-brygga

Version 0.6 använder ett hybridflöde. EutherVox sparar först en privat, användarknuten TOML-lista på gatewayen. Om samma användare har kopplat sitt Google-konto söker gatewayen låtar, sparar de providerneutrala referenserna lokalt, skapar en privat spegling i YouTube och öppnar den i YouTube Music. Exempel:

```text
Skapa en spellista med mörk svensk synth för verkstaden.
```

Appen visar sökningen och knapparna `Skapa privat lista` och `Avbryt`. Utan OAuth sparas fortfarande listans titel och stämningsfråga lokalt och YouTube Music får frågan som vanlig uppspelningssökning. Med OAuth får användaren dessutom en exakt privat YouTube-lista som är lätt att öppna på telefonen och senare casta till rumsnoder.

Varje lista ligger under `state/playlists/` som en läsbar TOML-fil med ägare, lokal UUID, fråga, tidsstämplar, låtreferenser och eventuell YouTube-listidentifierare. Filnamnets användardel är hashad, katalogen får rättighet 0700 och filerna 0600. TOML-listan är systemets original; YouTube-listan är en spelbar spegling som kan återskapas. Rått ljud lagras aldrig.

### Engångskonfiguration för Google

1. Skapa eller välj ett projekt i Google Cloud Console och aktivera YouTube Data API v3.
2. Skapa en OAuth-klient av typen Web application.
3. Lägg till exakt redirect-URI `https://apothictech.se/euthervox/oauth/callback`.
4. Skapa filen `~/.config/euthervox/youtube.env` utanför repot:

```dotenv
EUTHERVOX_GOOGLE_CLIENT_ID=din-klient-id
EUTHERVOX_GOOGLE_CLIENT_SECRET=din-klienthemlighet
```

Skydda filen med `chmod 600`, installera om `deploy/euthervox-gateway.service`, kör `systemctl --user daemon-reload` och starta om gatewayen. Öppna sedan Inställningar i appen och tryck `Koppla YouTube-konto`. OAuth-start, callback och status ligger under `/euthervox/oauth/` och ska skyddas av samma EutherOxide-behörighet som WebSocket-rutten. EutherOxide vidarebefordrar den verifierade identiteten i `X-Euther-User`; gatewayen binder OAuth-state och token till den användaren. Tokenfilerna lagras hashat och separat under `state/youtube-tokens/` med rättighet 0600.

### Cast till rumsenhet

Version 0.6 har en experimentell, konfigurationsstyrd Cast-adapter. Säg exempelvis:

```text
Spela Ghost i köket.
Skapa en mörk synthspellista i köket.
Sätt ihop en spellista med svensk punk på Kök 2.
```

`output_room` normaliseras till `köket`. Gatewayen söker musik med den användarens OAuth, ansluter direkt till den uttryckligen konfigurerade Nest-enheten och använder YouTube Cast-controllern. Vid fel öppnas YouTube Music på telefonen som tidigare. Appens inställningar har även `Öppna YouTube Music / Cast` för manuell val av högtalare och volym.

Cast är avstängt i mockkonfigurationen. Real-beta-konfigurationen mappar aliaset `köket` till `Kök 2` på det lokala nätet. IP och Cast-UUID är enhetsmetadata, inte autentiseringshemligheter. Lägg till framtida rum under separata `[cast.rooms."alias"]`-tabeller.

Real-beta använder den experimentella backenden `direct_audio`. Gatewayen väljer först en video via användarens YouTube Data API-koppling, löser sedan en kortlivad AAC/M4A-URL med `yt-dlp` och skickar den till Nest-enhetens vanliga Cast mediareceiver. Mediefilen laddas inte ned, den signerade URL:en loggas eller lagras inte och faktisk `PLAYING`/`BUFFERING`-status krävs innan appen får en lyckad kvittens. Detta är en inofficiell YouTube-strömväg och kan påverkas av YouTube-förändringar eller tjänstevillkor.

Den äldre `youtube_controller` finns kvar som valbar backend för experiment, men fungerar inte stabilt på Nest Mini: den kan starta YouTube-mottagaren utan att skapa en mediasession. Båda backendvarianterna har hårda tidsgränser och telefonfallback. Första Cast-starten spelar endast första sökträffen; automatisk köning återstår.

## MCP och modellstyrda verktyg

Version 0.7 har ett riktigt MCP-servergränssnitt och Ollama tool-calling ovanpå samma vitlistade verktygsregister. Den deterministiska kommandotolkaren körs först för lägsta möjliga latens. Om den inte känner igen ett musikrelaterat yttrande får Qwen välja mellan `music_play` och `playlist_create`. Ett exempel som nu går genom modellverktyget är:

```text
Jag är sugen på mörk cyberpunk i köket.
```

Modellens returvärde är aldrig direkt behörighet att agera. Registret avvisar okända verktyg, extra argument, okända rum, tomma frågor och godtyckliga nätverksmål. Nodnamn och autentiserad användare kommer från WebSocket-sessionen, inte från modellen. `playlist_create` behåller appens obligatoriska bekräftelse.

Den fristående MCP-servern använder officiella MCP Python SDK 2 och kör stdio som standard:

```bash
uv run euthervox-mcp --config config.real-beta.example.toml
```

Den exponerar:

- `cast_list_targets`: läser tillåtna rumsalias utan att lämna ut IP eller Cast-UUID.
- `music_play`: skapar ett validerat uppspelningsförslag.
- `playlist_create`: skapar ett validerat förslag som kräver bekräftelse.
- `wikipedia_lookup`: hämtar titel, artikelinledning och käll-URL skrivskyddat från svenska Wikipedia.

Skinnskattaren kan använda samma Wikipedia-verktyg direkt i röstflödet. Exempel:

```text
Berätta om Skinnskatteberg.
Sammanfatta Novemberrevolutionen från Wikipedia.
Läs inledningen av Wikipedia-artikeln om järnmalm.
```

Sammanfattningen grundas på den hämtade artikelinledningen, läses upp med figurens vanliga röst och visas tillsammans med källänken. Konfigurationen ligger under `[wikipedia]`; verktyget är skrivskyddat och API-adressen kan inte påverkas av modellen.

En extern MCP-klient får endast validerade åtgärdsförslag för musik och spellistor. Det skrivskyddade Wikipedia-verktyget får däremot hämta källtext direkt; faktisk musik- och enhetsstyrning sker fortfarande inne i en autentiserad EutherVox-session. Se [MCP-verktygsdesignen](docs/mcp-tools.md).

## Tester

```bash
pytest -q
./gradlew testDebugUnitTest
```

Python-sviten innehåller sessionsenhetstester och ett integrationstest över en riktig lokal WebSocket. Android-sviten testar protokollserialisering och parsning.

## Ansluta riktiga modeller

Implementera gränssnitten `SpeechToTextEngine`, `TextGenerationEngine` och `TextToSpeechEngine` i `server/gateway/adapters.py`. Lägg provider-valet i en fabrik som läser `[stt]`, `[llm]` respektive `[tts]` ur TOML och injicera implementationerna i `VoiceSession` i `main.py`. Modellkod ska inte läggas i WebSocket-lagret.

En STT-adapter får PCM som bytes och samplingsfrekvens. Generatorn är en async iterator så att textdelta kan skickas direkt. TTS-adaptern är också en async iterator och ska ge små PCM-block; klienten behöver därför inte ändras när en verklig lokal motor kopplas in. `AudioStreamFormat` och ljudgränssnitten avskiljer PCM-detaljerna så att Opus senare kan införas bakom nya implementationer.

Figurernas personligheter och röstparametrar ligger separat i
`characters/skinnskattaren.toml` och `characters/christian-grosshandlare.toml`.

## Kända begränsningar

- Standardprofilen `config.example.toml` är fortfarande den deterministiska mock-kedjan; välj uttryckligen en real-beta-konfiguration för riktigt tal.
- Den riktiga STT-profilen skickar i denna slice sin första `stt.partial` precis före `stt.final`; inkrementell avkodning medan knappen hålls inne återstår.
- Endast ett aktivt yttrande per WebSocket stöds avsiktligt.
- Samtalsminnet lever endast under aktuell WebSocket-session och omfattar högst tio turer/12 000 tecken; det är inte ett beständigt långtidsminne.
- Automatiskt röstavbrott beror på telefonens akustiska ekosläckning och behöver kalibreras på fysisk hårdvara. Den manuella `Avbryt och tala`-knappen är den stabila reservvägen.
- Musikstarten använder Androids dokumenterade sök-/uppspelnings-intent. Exakt träff och om uppspelningen startar direkt bestäms av den installerade YouTube Music-versionen och måste provas på fysisk telefon.
- Låtvalet är en första beta: en YouTube-sökning i musikkategorin används, inte YouTube Musics privata rekommendationsmotor. Granska därför listan efter skapande.
- En lokal lista som skapats utan OAuth innehåller tills vidare bara titel och stämningsfråga. Separat kommandoflöde för att synka en äldre lokal lista efter OAuth-koppling återstår.
- YouTube Data API:s standardkvot begränsar hur många sökningar och låtinfogningar som kan göras per dygn.
- Cast-adaptern använder PyChromecast och en inofficiell `yt-dlp`-resolver eftersom Google saknar ett publikt YouTube Music-uppspelnings-API för serverstyrda mottagare. Den är därför beta, konfigurationsstyrd och faller tillbaka till telefonen.
- Cast-kö, volym, nästa och dirigering till fler rum återstår.
- MCP-servern använder än så länge stdio och utför inte fristående åtgärder; autentiserad Streamable HTTP kan läggas till när en extern agent behöver fjärrstyra EutherVox.
- Prototypens WebSocket-klient stöder kompletta, ofragmenterade serverframes upp till 1 MiB.
- `ws://` är okrypterat och ska bara användas på betrott LAN. Den publika betarutten använder EutherOxide-inloggning och `wss://`.
- Ingen wake word, bakgrundsavlyssning eller långtidsminne finns.
- Android UI-test på fysisk enhet och mätning över verkligt Wi-Fi ingår inte i den automatiska testsuiten.

## Uppmätta lokala modellvärden

På utvecklingsmaskinen, med GPU:n samtidigt upptagen av ett annat jobb:

| Motor | Test | Resultat |
| --- | --- | --- |
| Piper `sv_SE-nst-medium` | 4,1 s svenskt ljud | första/hela meningen syntetiserad på 220 ms efter varmstart |
| faster-whisper `base`, CPU int8 | 2,0 s syntetiskt svenskt tal | 0,47–0,59 s, men ett ord blev feltolkat |
| faster-whisper `small`, CPU int8 | samma ljud | 1,59 s och exakt transkribering |
| faster-whisper `small`, CUDA float16 | 2,16 s syntetiskt svenskt tal | 52 ms varm, exakt transkribering; 402 ms första körningen |
| Qwen3 4B Instruct via Ollama | kort svensk fråga | 552 ms till första token, 647 ms totalt varm; 4,45 s kallstart |
| Komplett WebSocket-kedja | 2,12 s svenskt PCM, varm kedja | STT final 78 ms, första text 658 ms, första TTS-frame 989 ms efter `audio.end` |

Mätningen är vägledande och gjord medan ett annat GPU-jobb använde cirka 13 GB VRAM. Fysisk svensk mikrofoninspelning över Wi-Fi är nästa kvalitetsgrind.

Piper-runtimen `piper-tts` 1.6 är GPL-3.0-or-later. Den valda `nst`-rösten är tränad av KB-Lab på CC0-data; modellfilerna distribueras inte i detta repo.

Se [protokolldokumentet](docs/protocol-v1.md) för frame-koppling, tillstånd och fel.
