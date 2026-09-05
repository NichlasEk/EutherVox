# EutherVox beta 22: temperatur och program vid direktstart

2026-09-05. EutherVox 0.19.0-beta.22, versionCode 46.

Tvättkortet har Program och Temperatur bredvid varandra ovanför Starta, separat från datum/tid i schemaläggningskortet. Valen återanvänds för direktstart och schemaläggning. Startdialogen visar valt program och temperatur. Bekräftad direktstart skickar program_code och water_temperature genom EutherVox till EutherWash.

EutherWash utför programval, temperaturval, avläsning av båda valen och start under samma enhetslås. Ogiltiga eller obekräftade inställningar hindrar start. Smart Control och idle krävs fortsatt. Ett väntande eller pågående schema måste avbrytas före direktstart. Schemalagda starter använder samma sammanhållna startfunktion.

## Verifiering

- EutherWash: 81 tester passerar, inklusive varmt/kallt vatten, felaktigt temperaturvärde, utebliven readback, aktivt schema och API-autentisering/bekräftelse.
- EutherVox gateway: 71 berörda tester passerar.
- Android: 45 tester, assembleDebug och lintDebug godkända.
- Android 11-emulator: valde Bomull och 60 °C i direktstartsraden, såg samma val i bekräftelsen och bekräftade mot en syntetisk WebSocket-server. Servern tog emot command=start, confirmed=true, program_code=25, water_temperature=60. Ingen riktig tvätt startades.
- Visuell kontroll av raden och schemaläggningskortet i emulatorn.
- Signeringen matchar beta 20/21 så uppdatering kan installeras ovanpå befintlig app.

APK SHA-256: `85dec756661d0968da17c6d02177291f9ae1cbd5bdd1c2adccc4b297272041f7`.
Serverfil: `/home/nichlas/EutherVox-0.19.0-beta22-debug.apk`.
Androidkälla: `/home/nichlas/EutherVox`.
EutherWash kör på 192.168.32.186 som användartjänsten eutherwash.service; dess fyra ändrade runtime-filer matchade lokal HEAD före uppdatering. Backup: `/home/nichlas/EutherWash/backups/beta22-20260905`.
Gateway kör på arbetsstationen som användartjänsten euthervox-gateway.service.
EutherOxide serverbackup: `.euther-host/backups/euthervox-beta22-20260905/`.
