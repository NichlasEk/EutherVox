# Ebbas spellistor — EutherVox beta 34

Klistra in en offentlig eller olistad YouTube/YouTube Music-spellista under
Ebba som jukebox och tryck Spela på Ebba. En länk med både `v` och `list`
spelar spellistan från början. Högst de första 50 posterna hämtas; privata
eller borttagna poster som går att identifiera filtreras bort. Privata listor,
personliga mixar, Gillade videor och kontobundna radiolistor stöds inte här.
Vanlig låtsökning och länkar till enskilda låtar fungerar som förut.

Appen visar spellistenamn, aktuell plats i kön, de tre följande titlarna och
Nästa låt. Paus/Fortsätt behåller kön. Stoppa musik tömmer den. Volymändringar
följer med till nästa låt. Varje låt har fortfarande en gräns på 20 minuter.

## Livscykel och företräde

Kön ligger i den gemensamma gatewayprocessen, inte i en telefon/WebSocket-session.
Alla anslutna telefoner styr samma Ebba. Stängd app påverkar inte låtbytena.
Gatewayomstart tappar kön; redan pågående EutherWash-ljud kan fortsätta, men
spellistan måste väljas igen. Kön sparas inte på disk.

EutherWash ger varje uppspelning ett nytt `playback_id`, markerar naturligt slut
med `ended`, och rapporterar `busy` tills ljudprocessen är stängd. Endast detta
slut ger automatisk övergång. Robotmeddelande, fel, budbärare, pratknapp eller
användarpaus ger inte automatisk återstart. Okänd uppspelningsidentitet efter
serveromstart eller annan styrning tömmer den gamla kön.

Varje ljud-URL hämtas först inför respektive låt genom den isolerade yt-dlp-
runtime som redan uppdateras automatiskt. Metadatahämtning har 30 sekunders
timeout och en gräns på 50 poster. Ljudhämtningen har 25 sekunders timeout.
Stoppa/Pausa avbryter väntan på automatisk nästa-låt-hämtning. Efter ett fel
krävs ett uttryckligt nytt försök, ingen oändlig automatisk hoppning.
Extraktorns embedding/flat-playlist-gränssnitt:
https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp

## Verifiering

- Gateway: tester för automatisk övergång utan telefon, paus/fel/stopp,
  uppspelningsbyte, volym, återförsök, avbruten hämtning och URL-validering.
- Offentlig testspellista läst med den verkliga isolerade runtime-processen.
- Android debugbygge och JVM-tester passerade; version 58 / 0.19.0-beta.34.
- APK-signaturen är samma som beta33.
- Ingen ny robotrörelse eller hörbar provuppspelning startades vid bygget.
  Verklig uppspelning av flera låtar ska provas hemma.

APK SHA-256: `14ddbb8541c7d4d102992aad90bcdb50eb487cf378278c5f6d58407e1a2f7735`.
