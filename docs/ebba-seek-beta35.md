# Kompakta musikkontroller — beta 35

Version 0.19.0-beta.35 (59) lägger till föregående låt och en spolningslist
med spelad tid/låtlängd. Föregående, paus/fortsätt, nästa och stopp är små
ikoner på en gemensam rad. Klickytorna är fortfarande 48 dp och har svenska
tillgänglighetsnamn. Spolningslisten ligger ovanför raden.

Föregående väljer föregående post i spellistan. På första posten går den till
början av samma låt. Spolning inom låten behåller kön och uppspelningsidentiteten.
Spolar man under paus startas inget ljud; Fortsätt börjar vid vald tid.
Under uppspelning stoppas och töms den gamla strömmen innan ffmpeg öppnas
med `-ss` på den nya positionen. Det blir därför en kort laddningspaus.

YouTube-hämtaren skickar nu `duration_seconds`. Högst de första 20 minuterna
är spelbara, som tidigare. Saknad längd ger en inaktiv spolningslist och
texten Längd okänd. Befintliga strömmar från en äldre serverversion behöver
väljas på nytt för att få längden. Spolpunkter utanför låten eller 20-minuters-
gränsen avvisas. Telefonen skickar även uppspelningsidentiteten från när
dragningen började, så ett låtbyte under dragningen inte spolar i fel låt.

Köns bevakning avbryts före föregående/nästa/spolning så att automatisk
övergång inte kan tävla med kontrollerna. Bevakningen återupptas även om en
spolningsbegäran avvisas. Befintlig fyrasekundersbuffert är kvar.

## Verifiering

- 89 gatewaytester passerade, inklusive föregående/första låten och bevarad kö.
- 63 musik-, intercom- och leveranstester passerade, inklusive paus vid spolning,
  gränser, ogiltiga värden och avvisning av gamla uppspelningsidentiteter.
- Android debugbygge och JVM-tester passerade, med samma APK-signatur som tidigare.
- Riktig duration från den isolerade hämtaren: Wagner-länken gav 307 sekunder.
- Tyst prov på Ebba: start vid 0, spolning till 20 under spelning, paus och
  spolning till 5 utan ljudstart, sedan Fortsätt. Avkodarens startpositioner
  verifierades som 0/20/5 och slutstoppet bekräftades.

EutherWash-backup på .186:
`~/.local/state/eutherwash/private/before-seek-beta35/`.

APK SHA-256: `475b7d2a6267a267abd6659e8b2c540970fde21ff780c6e7450ddee31e702726`.
