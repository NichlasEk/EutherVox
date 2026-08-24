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
`finished` skapar ett enda beständigt besked i
`state/washer-notification.json`. En omstart tappar därför inte ett pågående
varv eller ett väntande besked, men en första start där maskinen redan står i
`finished` spelar inte upp ett gammalt besked.

Den första autentiserade och lediga EutherVox-klienten får ett
`assistant.notification`, följt av samma PCM-ström som vanlig TTS. Om appen är
stängd väntar beskedet tills nästa anslutning. Versionen kör inte en permanent
Android-bakgrundstjänst.

Konfigurationen ligger under `[eutherwash]`:

```toml
notifications_enabled = true
notification_poll_seconds = 8
notification_voice = "moss-nano"
notification_state_file = "state/washer-notification.json"
notification_jingle_file = "assets/audio/washer-complete.wav"
```

Jingeln är en åtta sekunder lång lokal ACE-Step 1.5-generering, verifierad med
den svenska STT-modellen och konverterad till mono PCM s16le vid 22050 Hz. Den
spelas före MOSS-Nano-rösten. Om filen saknas läses beskedet fortfarande upp;
om MOSS inte svarar använder TTS-routern sin konfigurerade Piper-reserv.
