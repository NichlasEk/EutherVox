# Toshiba-pump helt lokalt via EutherPump

## Målet är ett krav, inte en framtida förbättring

Den slutliga produktionsvägen ska vara:

```text
Toshiba-pump ⇄ originaladapter ⇄ lokalt TLS/MQTT ⇄ EutherPump ⇄ EutherVox
```

Toshibas konto, app, API och Azure-instans får användas som forskningsmaterial
under utvecklingen, men får inte vara ett runtime-beroende. Integrationen är
inte färdig förrän följande acceptanstest passerar:

1. Blockera värmepumpens internetåtkomst i routern.
2. Starta om adapter, EutherPump och EutherVox.
3. Läs aktuell temperatur, börvärde, läge och driftstatus.
4. Ändra ett ofarligt värde, läs tillbaka det från pumpen och återställ det.
5. Verifiera att ingen DNS-, HTTP-, MQTT- eller annan anslutning lämnar LAN.

Om originaladaptern inte kan flyttas säkert till den lokala tjänsten är en
reversibel UART-adapter reservvägen. En molnproxy är inte en reservväg.

## Bekräftat på den aktuella installationen

- Pumpfamiljen är Toshiba Daiseikai 9 PKVPG med original Wi-Fi-adapter.
- Adaptern har hittats på LAN som `192.168.32.12`; adressen är observation, inte
  en stabil identitet och ska inte hårdkodas.
- Installerad adapterfirmware är `4.4.00`. Toshiba erbjuder `5.0.00` med
  beskrivningen “Upgrade TLS cipher suites”, men uppdateringen har inte
  installerats.
- Den hämtade officiella 5.0.00-filen är 554015 byte och har SHA-256
  `d4849e4d8b524bd71e4da3cc2c61cd21f0de41fbe30d501b94f8743749f99ace`.
- Firmwareformat och adresser identifierar adaptern som Realtek
  RTL8195A/Ameba-baserad.
- Port 80 på adapterns vanliga LAN-adress avvisar anslutningar.
- I adapterläge använder originalappen ett lokalt HTTP-API på `192.168.1.1`:
  `GET/POST /device_info`, `POST /wps_info` och `POST /scan_info`.
- Lokal setup skickas som JSON och kan ange Wi-Fi, OTA-värd/-fil/-port,
  certifikatvärd/-fil/-port samt provisioning-värd/-port.
- Firmware innehåller de två provisioningvägarna
  `/api/Provision/RegisterACSecure` och `/api/Provision/RegisterAC`.
- Secure-requesten är JSON och innehåller åtminstone `DeviceType` och `ModelId`.
  `DeviceType` är konstanten `"2"`; `ModelId` formateras som hex.
- Adaptern förväntar sig ett JSON-svar med strängarna `SasToken`, `HostName`
  och `DeviceId` under `ResObj`. Den närliggande strängen `Connection` används
  som HTTP-header tillsammans med värdet `close`; den är inte ett fjärde
  provisioningfält.
- Firmware innehåller Azure IoT-liknande MQTT-ämnen, bland annat
  `$iothub/methods/POST/#` och `$iothub/methods/res/...`.
- MQTT-anslutningen går till `HostName` på TLS-port 8883 med 60 sekunders
  keepalive. `DeviceId` används som client ID. Användarnamnet byggs som
  `HostName/DeviceId/api-version=2016-11-14` och hela `SasToken` används som
  lösenord.
- Telemetri publiceras under `devices/DeviceId/messages/events/...`; adaptern
  prenumererar på Azure-formatets direct-method-ämne.
- Den analyserade anslutningsvägen delar inte upp eller verifierar SAS-tokenens
  `sr`, `sig` eller `se`. Provisioningkoden kräver att strängen är icke-tom och
  MQTT-koden skickar den sedan oförändrad som lösenord. Detta ska bekräftas i
  det isolerade adapterprovet, men en Azure-signerad token ser inte ut att
  behövas när även brokern är lokal.
- Firmware innehåller en gemensam provisioning-identitet, inte en unik
  privat nyckel per observerad adapter. Nyckelmaterialet ska aldrig läggas i
  repot eller loggas.

Enhetens molnstatus visar dessutom att state-payloaden använder den redan
reverse-engineerade Toshiba-hexsträngen. Det är alltså transporten och
provisioneringen som återstår; vi behöver inte uppfinna pumpens tillståndsmodell
från början.

## Trolig lokal väg

Följande är en hypotes tills den har bekräftats med ett isolerat fysiskt prov:

1. Originaladaptern sätts tillfälligt i sitt installations-/AP-läge.
2. Dess certifikatkälla pekas på en lokal EutherPump-CA.
3. Dess provisioningvärd pekas på EutherPump.
4. EutherPump svarar med lokal brokeradress, lokalt enhets-ID, ett icke-tomt
   lokalt lösenord i `SasToken` och de tre förväntade `ResObj`-fälten.
5. Adaptern ansluter med TLS till en lokal MQTT-broker som accepterar dess
   Azure-formade klientdialog.
6. EutherPump översätter direct methods och state-events till sitt rena lokala
   API. EutherVox ser bara allowlistade, namngivna pumpar.

Detta är inte traditionell TLS-knäckning. Vi försöker använda adapterns egen
provisioneringsmekanism för att välja en lokalt ägd CA och tjänst. Det som ännu
är okänt är exakt TLS-certifikatbeteende och vilka delar av direct-method-
dialogen adaptern kräver efter anslutning.

## Genomförandeordning

### 1. Emulering utan att röra pumpen

- Implementera en liten lokal provisioningserver med strikt loggning utan
  hemligheter.
- Implementera en TLS-testbroker eller adapter framför en vanlig MQTT-broker.
- Reproducera de förväntade Azure-ämnena och bygg fixtures för kända state-
  payloads.
- Lägg till paketspårning som kan bevisa att all trafik stannar på LAN.

### 2. Kontrollerat adapterprov

- Exportera nuvarande observerbara konfiguration innan någon ändring.
- Stoppa om det inte finns en verifierad återställningsväg till originalskick.
- Provisionera mot ett isolerat test-LAN utan internet.
- Börja med anslutning och read-only state. Skicka inga skrivkommandon.

### 3. Verifierad fysisk styrning

- Lägg till ett enda ofarligt, reversibelt kommando.
- Kräv readback från pumpen; MQTT-ack räcker inte.
- Utöka därefter till mode, temperatur, fläkt och swing med registergränser och
  interlocks i EutherPump.
- Aktivera EutherVox skrivverktyg först när fysisk readback är stabil.

### 4. Molnfri leverans

- Blockera WAN och kör acceptanstestet överst i dokumentet.
- Dokumentera återställning, backup och hur originalfirmware behålls intakt.
- Spara inga Toshiba-inloggningar, signerade URL:er, certifikatnycklar eller
  råa enhetsidentifierare i Git.

## Säkerhetsgräns just nu

Firmware 4.4.00 sitter kvar i adaptern. Firmware 5.0.00 har bara analyserats
offline. Ingen firmwareinstallation, omprovisionering eller extern
enhetsregistrering ska göras utan ett separat, uttryckligt beslut efter att
återställningsvägen har verifierats.
