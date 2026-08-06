# EutherVox 0.1

EutherVox är en lokal, strömmande röstprototyp. Android-telefonen står för mikrofon, högtalare och UI; gatewayen tar emot rå PCM över WebSocket och kör en utbytbar STT → figur → textgenerator → TTS-kedja.

Den inbyggda mock-kedjan kräver inga AI-modeller. Den transkriberar till `Var ligger min lödkolv?`, svarar som Skinnskattaren och strömmar en kort testton som TTS-ljud. Tonen gör att hela ljudvägen kan verifieras, men är inte syntetiserat tal.

Det finns även en riktig svensk betaprofil: flerspråkig faster-whisper för STT, en liten svensk-capabel Qwen-modell via Ollama och Piper `sv_SE-nst-medium` för snabb CPU-TTS. Dots/VoxCPM är avsiktligt inte dialogstandard; de passar bättre som valbara kvalitetsmotorer för längre uppläsning.

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
ollama pull qwen3:4b-instruct
```

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

Båda profilerna låser `language = "sv"` och `task = "transcribe"`, så den engelska snabbmodellen används inte och tal översätts inte till engelska. Lägg bara till `hotwords` efter mätning; en bred ordlista visade sig kunna förvränga vanliga svenska fraser. Gatewayen värmer STT och Qwen före den börjar lyssna; Ollama håller sedan Qwen varm i 30 minuter för att undvika dess uppmätta kallstart på cirka 4,45 sekunder.

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

Android spelar inte in till fil och loggar inte rått ljud. Mikrofonen startas först efter `audio.start` och frigörs vid finger upp, avbruten gest, paus eller fel. En begränsad kö mellan AudioRecord och WebSocket gör att nätverksstopp inte blockerar ljudinläsningen; tappade block visas i UI.

## Tester

```bash
pytest -q
./gradlew testDebugUnitTest
```

Python-sviten innehåller sessionsenhetstester och ett integrationstest över en riktig lokal WebSocket. Android-sviten testar protokollserialisering och parsning.

## Ansluta riktiga modeller

Implementera gränssnitten `SpeechToTextEngine`, `TextGenerationEngine` och `TextToSpeechEngine` i `server/gateway/adapters.py`. Lägg provider-valet i en fabrik som läser `[stt]`, `[llm]` respektive `[tts]` ur TOML och injicera implementationerna i `VoiceSession` i `main.py`. Modellkod ska inte läggas i WebSocket-lagret.

En STT-adapter får PCM som bytes och samplingsfrekvens. Generatorn är en async iterator så att textdelta kan skickas direkt. TTS-adaptern är också en async iterator och ska ge små PCM-block; klienten behöver därför inte ändras när en verklig lokal motor kopplas in. `AudioStreamFormat` och ljudgränssnitten avskiljer PCM-detaljerna så att Opus senare kan införas bakom nya implementationer.

Figurens personlighet och röstparametrar ligger separat i `characters/skinnskattaren.toml`.

## Kända begränsningar

- Standardprofilen `config.example.toml` är fortfarande den deterministiska mock-kedjan; välj uttryckligen en real-beta-konfiguration för riktigt tal.
- Den riktiga STT-profilen skickar i denna slice sin första `stt.partial` precis före `stt.final`; inkrementell avkodning medan knappen hålls inne återstår.
- Endast ett aktivt yttrande per WebSocket stöds avsiktligt.
- Prototypens WebSocket-klient stöder kompletta, ofragmenterade serverframes upp till 1 MiB.
- `ws://` är okrypterat. `wss://` stöds av transporten, men certifikat och autentisering är inte konfigurerade.
- Ingen extern autentisering, wake word, bakgrundsavlyssning eller långtidsminne finns.
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
