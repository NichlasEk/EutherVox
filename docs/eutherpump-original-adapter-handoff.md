# Handoff: Toshibas originaladapter till lokal EutherPump

## Öppna rätt arbetsyta

Fortsätt i:

```text
/home/nichlas/EutherPump
```

Läs först dessa två dokument i EutherVox:

- `/home/nichlas/EutherVox/docs/eutherpump-toshiba-local.md`
- `/home/nichlas/EutherVox/docs/toshiba-original-adapter-protocol.md`

De är pushade på EutherVox `main`. Relevant dokumentationscheckpoint är
`1790d50`; efterföljande `main` kan innehålla denna handoff.

## Målet får inte ändras

Produktionsvägen ska vara helt lokal:

```text
Toshiba-pump ⇄ originaladapter ⇄ lokal TLS/MQTT ⇄ EutherPump ⇄ EutherVox
```

Toshiba-konto, Toshiba API och Azure får bara vara forskningskällor. Sluttestet
är omstart och full read/write/readback med pumpens WAN blockerat samt
paketspårning som visar att ingen trafik lämnar LAN.

ESP32/UART är en reversibel reservväg, inte den aktuella implementationen.

## Aktuellt fysiskt läge

- Originaladaptern sitter kvar och fungerar mot Toshiba Cloud.
- Installerad firmware är fortfarande 4.4.00.
- Toshibas erbjudna 5.0.00 har hämtats och analyserats offline men inte
  installerats.
- Pumpen har inte omprovisionerats.
- Ingen extern enhetsregistrering har gjorts av våra verktyg.
- Telefonens tillfälliga ADB-proxy/reverse är borttagen och Android-proxyn är
  återställd.

Gör ingen firmwareinstallation, omprovisionering eller fysisk styrning utan en
separat säkerhetskontroll och en verifierad återställningsväg.

## Verifierat protokoll

Provisioningrespons:

```json
{
  "ResObj": {
    "SasToken": "<non-empty-local-password>",
    "HostName": "<local-broker-hostname>",
    "DeviceId": "<local-device-id>"
  }
}
```

MQTT:

```text
TLS port:  8883
client id: DeviceId
username:  HostName/DeviceId/api-version=2016-11-14
password:  SasToken, passed through unchanged
keepalive: 60 seconds
subscribe: $iothub/methods/POST/#
uplink:    devices/DeviceId/messages/events/%24.ct=application%2Fjson&%24.ce=utf-8&type=ac
```

Direct method:

```text
$iothub/methods/POST/smmobile/?$rid=<request-id>
```

Command body uses `sourceId`, `messageId`, `targetId`, `cmd`, `payload` and
`timeStamp`. State write command is `CMD_FCU_TO_AC`; state uplink is
`CMD_FCU_FROM_AC`; heartbeat is `CMD_HEARTBEAT`.

Adapter ACK:

```text
topic: $iothub/methods/res/123/?$rid=<request-id>
body:  {"ACK":"ACK"}
```

Status `123` is from the original firmware and must not be normalized to 200 in
the broker compatibility layer.

## Färdig prototyp i /tmp

Om `/tmp` finns kvar ligger en standardbiblioteksbaserad MQTT 3.1.1-prototyp
här:

```text
/tmp/eutherpump-original-adapter-prototype/toshiba_mqtt.py
/tmp/eutherpump-original-adapter-prototype/tests/test_toshiba_mqtt.py
```

Den innehåller:

- `AdapterIdentity` och exakt provisioningrespons;
- MQTT remaining-length codec och packet reader;
- strikt CONNECT-validering;
- SUBSCRIBE-allowlist;
- uplink PUBLISH/JSON-validering;
- PINGREQ/PINGRESP och DISCONNECT;
- direct-method PUBLISH-builder;
- `AdapterSession` med read-only uplink callback.

Verifierat testkommando:

```bash
PYTHONPATH=/tmp/eutherpump-original-adapter-prototype \
  python3 -m unittest discover \
  -s /tmp/eutherpump-original-adapter-prototype/tests -v
```

Resultat: 9/9 tester passerar, inklusive riktig loopback-TCP för hela sekvensen
CONNECT, CONNACK, SUBSCRIBE, SUBACK, PUBLISH, PINGRESP och DISCONNECT.

Loopbacktestet kan behöva sandboxgodkännande för att skapa en socket. Om `/tmp`
har rensats ska prototypen rekonstrueras från wire-specen; gissa inte fram
andra topics eller Azure-beteenden.

## EutherPump-läge före fortsatt arbete

Senast verifierad EutherPump `main` var ren och innehöll dessa commits:

```text
c093590 Bootstrap local EutherPump service
6e9b309 Add read-only Toshiba UART capture backend
c7f319b Add offline Toshiba capture decoder
d154599 Add encrypted ESPHome state backend
1ecf68f Wire ESPHome backend into daemon runtime
```

35 tester passerade då. Kontrollera alltid aktuell `git status`, `git log` och
testsvit på nytt; ovanstående är en handoff-snapshot, inte skäl att skriva över
nyare arbete.

Viktiga filer:

```text
src/eutherpump/api.py
src/eutherpump/runtime.py
src/eutherpump/backend.py
src/eutherpump/models.py
src/eutherpump/service.py
config/eutherpump.example.toml
tests/test_runtime.py
```

`create_app()` äger FastAPI-routes och kan livscykelhantera vald backend.
`load_backend()` väljer idag simulator eller ESPHome. Originaladaptervägen ska
läggas som en separat backend utan att försvaga simulatorns säkra default eller
ESPHome-backendens validering.

## Rekommenderad implementationsordning

### Checkpoint 1: ren wire-codec

1. Flytta prototypen till exempelvis
   `src/eutherpump/toshiba_original_mqtt.py`.
2. Porta testerna till pytest-stilen i EutherPump.
3. Lägg gränser för packetstorlek och JSON-storlek innan någon socket exponeras.
4. Tillåt endast en konfigurerad adapteridentitet och exakt uplink-topic.
5. Kör hela befintliga testsviten.
6. Commit och push denna slice innan FastAPI eller runtime ändras.

### Checkpoint 2: konfiguration och read-only backend

1. Lägg backendnamn `toshiba_original` i `runtime.py`.
2. Läs lokalt MQTT-lösenord via namngiven miljövariabel; lagra det inte i TOML,
   repr eller logg.
3. Kräv privat bind-adress/hostname, explicit pump-ID, adapter-ID och TLS-filer.
4. Starta/stoppa TLS/MQTT-servern i backendens livscykel.
5. Avkoda endast `CMD_FCU_FROM_AC` och `CMD_HEARTBEAT` till normaliserad
   `PumpState`.
6. Backend ska rapportera read-only capabilities och avvisa alla PATCH-anrop.
7. Lägg syntetiska fixtures utan riktiga enhets-ID:n.

### Checkpoint 3: lokal provisioningserver

1. Servera endast CA-certifikatet, aldrig CA-privatnyckeln.
2. Lägg de exakta Toshiba-routes som separata FastAPI-routes:
   `/api/Provision/RegisterAC` och vid behov `RegisterACSecure`.
3. Validera `DeviceType == "2"` och tillåten `ModelId`; logga inte requestbody
   okontrollerat.
4. Returnera enbart konfigurerad lokal `HostName`, `DeviceId` och ett lokalt
   lösenord.
5. Provisioning ska vara explicit aktiverad, tidsbegränsad och avstängd som
   normal default.

För första isolerade provet är icke-443 `RegisterAC` sannolikt enklare än att
emulera Toshibas ömsesidiga TLS-provisioning. Det är endast acceptabelt på ett
isolerat nät utan WAN. MQTT-driften ska fortfarande använda lokal TLS.

### Checkpoint 4: isolerat adapterprov

1. Dokumentera och verifiera återställning innan adapterkonfiguration ändras.
2. Använd separat test-LAN utan internet.
3. Börja med CA-hämtning, provisioning, MQTT CONNECT och SUBSCRIBE.
4. Ta endast emot uplink. Skicka inte `CMD_FCU_TO_AC`.
5. Identifiera `targetId` från verifierad read-only data; gissa eller hårdkoda
   inte molnidentifierare.
6. Verifiera QoS, första state-body, reconnect, DNS- och brokeravbrott.

### Checkpoint 5: ett reversibelt skrivprov

Görs först efter uttryckligt beslut. Skicka ett enda gränskontrollerat kommando,
kräv state-uplink/readback från pumpen, återställ värdet och håll EutherVox
skrivverktyg avstängda tills detta är stabilt.

## Säkerhetskrav i kod

- Ingen Toshiba-login, moln-SAS, signerad URL eller privat nyckel i Git/logg.
- MQTT-broker bindas endast på uttrycklig privat adress, aldrig implicit
  `0.0.0.0`.
- TLS är obligatoriskt för normal MQTT-drift.
- Adapter-IP/client ID/username/topic allowlistas.
- Packet- och JSON-storlek begränsas före allokering.
- Provisioning är separat från språkmodell och röstverktyg.
- Readback från fysisk pump krävs innan ett kommando rapporteras lyckat.
- WAN-blocktestet är leveranskrav, inte valfri härdning.

## Första meddelandet i nästa session

En lämplig fortsättningsinstruktion är:

```text
Fortsätt handoffen i
/home/nichlas/EutherVox/docs/eutherpump-original-adapter-handoff.md.
Arbetsytan är nu /home/nichlas/EutherPump. Börja med checkpoint 1, kör hela
testsviten, commit och push när slicen är grön. Rör inte pumpen ännu.
```
