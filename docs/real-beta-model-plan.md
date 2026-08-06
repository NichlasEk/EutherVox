# Modellplan för svensk samtalsbeta

## Beslut

Dialogvägen ska inte vänta på Dots eller VoxCPM. EutherVox använder tre oberoende profiler:

- `dialog-fast-sv` (beta-standard): faster-whisper `small` med CUDA float16, Qwen3 4B Instruct och Piper `sv_SE-nst-medium`.
- `dialog-cpu-sv` (reserv): faster-whisper `base` med CPU int8, samma generator och Piper.
- `narration-quality-sv`: framtida valbar Dots/VoxCPM-väg för längre uppläsning, inte push-to-talk-standard.

Den snabba engelska GrapheneOS Matcha-rösten behålls där engelska uttryckligen efterfrågas. Den ska inte användas för svensk text, eftersom problemet är språkstöd och inte bara modellhastighet.

## Varför denna uppdelning

GPU:n kan redan vara upptagen av musik-, bild- eller bokjobb. Whisper Small använde ändå en liten nog resursandel för att samexistera med ett 13 GB-jobb och Qwen 4B på RTX 4090. Den gav exakt transkribering av 2,16 sekunders syntetiskt svenskt tal på 52 ms varm. CPU-profilen finns kvar för drift utan CUDA. Piper genererade lokalt cirka 4,1 sekunder svenskt ljud på 220 ms efter varmstart.

Den verifierade varma WebSocket-rundresan gav `stt.final` efter 78 ms, första textdelta efter 658 ms och första TTS-frame efter 989 ms från `audio.end`. Modellerna värms innan gatewayen öppnar porten. Qwen-profilen förbjuder också påhittade platser; utan faktaunderlag ska figuren säga att den inte vet och be om relevant information.

## Återstående kvalitetsgrindar

1. Spela in 15–20 korta svenska yttranden på den fysiska telefonen.
2. Mät ordnoggrannhet och final-latens för CUDA-profilen och CPU-reserven med identiska klipp.
3. Bekräfta standardprofilen med verkligt tal, inte bara Piper-genererat testljud.
4. Mät första uppspelade ljud över verkligt Wi-Fi; under en sekund efter knappsläpp är målet för varm modellkedja.
5. Publicera först därefter `0.2.0-beta.1` under EutherOxide Apps.

## Konfigurationsgränser

Alla motorer väljs i TOML. Android-klienten känner bara till protokollets annonserade PCM-format. Piper annonserar 22050 Hz i `tts.start`, medan mocken fortfarande annonserar 24000 Hz. Därmed krävs ingen klientändring när TTS-motor byts.
