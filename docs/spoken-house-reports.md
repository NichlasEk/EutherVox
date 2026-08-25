# Muntliga husrapporter och tvättbesked

EutherVox kan läsa två lokala rapporter utan att lämna frågan till språkmodellens
fria verktygsval:

- `Hur går tvätten?` och `Ge mig en tvättrapport` läser status, återstående tid
  och en kort sjudagarsstatistik från EutherWash.
- `Vad säger värmepumpen?` och `Ge mig en värmepumpsrapport` läser den valda
  pumpens temperatur och driftläge från EutherPump.

Rapportfrågorna är skrivskyddade. Tvätt- och pumpkommandon använder sina
separata autentiserings- och bekräftelsevägar.

## Klartbesked

Gatewayen frågar endast EutherWash fasta statusroute. När den har sett
`running` eller `paused` armeras det aktuella varvet. Nästa övergång till
`finished` skapar ett beständigt besked i `state/washer-notification.json` och
lägger det i en separat kö för varje känd EutherVox-enhet. En omstart tappar
därför inte ett pågående varv eller väntande besked, men en första start där
maskinen redan står i `finished` spelar inte upp ett gammalt besked.

Varje autentiserad och ledig EutherVox-klient får sitt eget
`assistant.notification`, följt av samma PCM-ström som vanlig TTS. Om en app är
stängd väntar just den enhetens besked tills nästa anslutning. Versionen kör inte
en permanent Android-bakgrundstjänst.

Konfigurationen ligger under `[eutherwash]`:

```toml
notifications_enabled = true
notification_poll_seconds = 8
notification_ai_enabled = true
notification_character = "skinnskattaren"
notification_voice = "moss-nano"
notification_state_file = "state/washer-notification.json"
notification_jingle_file = "assets/audio/washer-complete.wav"
```

Jingeln är en åtta sekunder lång lokal ACE-Step 1.5-generering, verifierad med
den svenska STT-modellen och konverterad till mono PCM s16le vid 22050 Hz. Den
spelas före MOSS-Nano-rösten. Om filen saknas läses beskedet fortfarande upp;
om MOSS inte svarar använder TTS-routern sin konfigurerade Piper-reserv.

När AI-läget är aktivt skapas texten en gång lokalt när färdigkanten upptäcks.
Text, händelse-id och vald röst köas därefter separat för varje känt
`node_name`. En upptagen eller frånkopplad enhet behåller sin egen beständiga
FIFO utan att stoppa uppläsning på andra enheter. Den fasta svenska texten är
reserv om den lokala språkmodellen inte svarar.
