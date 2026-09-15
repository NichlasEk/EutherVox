# Ebbas röstkommandon och lokala anslutning

Gatewayen har nu deterministiska kommandon för:

- Starta Ebba / Ebba, städa!
- Pausa Ebba
- Stoppa Ebba
- Skicka hem Ebba / Ebba åk hem / Skicka Ebba till laddaren
- Hur mår Ebba? (befintlig statusrapport med nytt alias)

Hela styrfrasen måste matcha. Negationer, hypotetiska formuleringar och okända
rumsmål matchar inte. Ebba lades också till i STT-hotwords. Rörelseverktyget
exponeras inte till språkmodellen; modellförslag på vacuum_control avvisas.
Explicit talad startorder räknas som användarens startbekräftelse. Detta ändrar
inte befintliga bekräftelser för appknappar eller snabbkartläggning. Ingen
röststyrd snabbkartläggning, rumsstyrning eller schemaläggning har lagts till.

Sessionen kräver inloggad användare och anropar befintliga EutherWash-klienten
med dess kontrolltoken, allowlist och tillståndskontroller. Kvittensen säger
att kommandot mottagits; den påstår inte att hemgången är färdig. Samma
protokollmeddelanden för text, TTS och status används som tidigare, utan APK-byte.

114 relevanta tester passerade: planering, negationer, avvisat modellstyrt
rörelseanrop, autentiserad/oautentiserad röstsession, TTS samt befintliga tester
för verktyg, session och EutherWash. Testerna använder syntetisk transkription;
en verklig mikrofonrunda från telefonen återstår.

## Faktisk driftväg

Gatewayen kör som användartjänsten `euthervox-gateway` på arbetsstationen
192.168.32.88:8788, inte på EutherOxide-servern. Gatewayens konfiguration pekar
på EutherWash på 192.168.32.186:8801. Caddy på EutherOxide autentiserar
`/euthervox/ws` och proxyar till 192.168.32.88:8788. Telefonens LAN-anslutning
och autentiserade WAN-anslutning använder alltså samma styrbackend.

Gatewayen startades om efter ändringen och är aktiv. Med robotens WAN blockerat
läste den riktiga EutherWash-klienten online/charging, två kartor och talbar
status. Det publika WS-endpointet gav 401 utan inloggning, som förväntat.
Ett fullständigt autentiserat test från en telefon på mobilnätet har inte gjorts.
Arbetsstationens gateway behöver fortsätta köra för denna arkitektur.

Robotens tillfälliga LAN-only-prov startade 22:02 svensk tid 2026-09-15 och
återställs automatiskt omkring 22:17 (eller vid omstart av roboten). Ingen
permanent routerregel installerades. SSH och LAN behölls; serverns WAN ändrades
inte. Se EutherWashs `docs/dreame-local-cloud-replacement-2026-09-15.md` för
testbevis, begränsningar och privata artefakter.

Sparade kartor kan hämtas lokalt. Färsk kart-/positionsström är fortfarande
öppen forskning och ska inte beskrivas som färdig i appen.
