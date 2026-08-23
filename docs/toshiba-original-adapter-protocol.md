# Protokollkontrakt för Toshibas originaladapter

Detta dokument är implementationsunderlaget för EutherPumps lokala emulator.
Det bygger på offlineanalys av adapterfirmware 5.0.00 och jämförelse med det
öppna biblioteket
[`KaSroka/Toshiba-AC-control`](https://github.com/KaSroka/Toshiba-AC-control).
Inga Toshiba-hemligheter, enhets-ID:n eller signerade URL:er hör hemma här.

## Provisioning

Originaladapterns installationsläge erbjuder HTTP på `192.168.1.1`:

- `GET/POST /device_info`
- `POST /wps_info`
- `POST /scan_info`

Setup-JSON kan ange Wi-Fi samt separata värdar, filer och portar för OTA,
CA-certifikat och provisioning. Adaptern anropar därefter någon av:

```text
POST /api/Provision/RegisterACSecure
POST /api/Provision/RegisterAC
Content-Type: application/json
```

Den analyserade requesten innehåller åtminstone:

```json
{
  "DeviceType": "2",
  "ModelId": "<lowercase-hex>"
}
```

EutherPump ska svara med exakt denna form:

```json
{
  "ResObj": {
    "SasToken": "<non-empty-local-password>",
    "HostName": "<local-broker-dns-name>",
    "DeviceId": "<local-device-id>"
  }
}
```

Alla tre värden måste vara strängar och icke-tomma. Firmware kopierar
`SasToken` oförändrad till MQTT-lösenordet; den analyserade vägen delar inte
upp eller verifierar en Azure-SAS-signatur.

`Connection: close` är en vanlig HTTP-header och inte ett fält i `ResObj`.

## CA och TLS

Adaptern kan hämta en CA-fil från den konfigurerade certifikatvärden. Standard-
sökvägen i firmware är `/certificate/ca_certificate.cer`, men setup-JSON kan
ange en annan fil och port.

Den nedladdade filen måste vara ett giltigt PEM-certifikat som kan parsas av
mbedTLS. MQTT-klienten använder det som root CA och avvisar anslutningen om
servercertifikatet inte verifieras. Den lokala lösningen behöver därför:

1. en EutherPump-ägd lokal CA;
2. ett broker-certifikat vars SAN matchar `HostName`;
3. lokal DNS eller annan stabil namnupplösning för samma namn;
4. en certifikatserver som bara exponeras under det kontrollerade
   provisioneringsförloppet.

Det första fysiska provet ska använda ett isolerat nät utan WAN. Privat CA-
nyckel ska aldrig skickas till adaptern eller checkas in; endast CA-certifikatet
distribueras.

## MQTT CONNECT

Den verifierade anslutningen är:

```text
transport: TLS
host:      HostName från provisioning
port:      8883
client id: DeviceId
username:  HostName/DeviceId/api-version=2016-11-14
password:  SasToken, oförändrad
keepalive: 60 sekunder
```

EutherPumps brokeradapter kan verifiera dessa värden lokalt. Den behöver inte
verifiera någon Azure-signatur och ska inte acceptera andra client ID:n,
användarnamn eller källadresser.

## Topics

Adaptern prenumererar på:

```text
$iothub/methods/POST/#
```

Det kända styrflödet använder methodnamnet `smmobile`:

```text
$iothub/methods/POST/smmobile/?$rid=<request-id>
```

Adaptern svarar med body `{"ACK":"ACK"}` på:

```text
$iothub/methods/res/123/?$rid=<request-id>
```

Statusdelen `123` är vad originalfirmware faktiskt använder. Emulatorn ska
registrera svaret och matcha `$rid`, inte förutsätta Azure-standardstatus 200.

Adapterns uplink publiceras under:

```text
devices/DeviceId/messages/events/%24.ct=application%2Fjson&%24.ce=utf-8&type=ac
```

## Toshiba-body

Ett styrkommando till adaptern har denna JSON-form:

```json
{
  "sourceId": "<local-controller-id>",
  "messageId": "<local-message-id>",
  "targetId": ["<ac-unique-id>"],
  "cmd": "CMD_FCU_TO_AC",
  "payload": {
    "data": "<Toshiba-state-hex>"
  },
  "timeStamp": "<local-timestamp>"
}
```

De öppna klienterna använder samma fältform mot Toshiba Cloud. Firmwareanalysen
bekräftar att adaptern parsar metadatafälten, `payload`, `data` och `cmd`.
`targetId` ska inte gissas: EutherPump ska först lära sig identiteten från
read-only uplink eller en lokalt exporterad, verifierad konfiguration.

Viktiga uplink-kommandon är:

- `CMD_FCU_FROM_AC`: `payload.data` innehåller pumpens state-hex.
- `CMD_HEARTBEAT`: `payload` innehåller bland annat temperatur- och driftdata.

## Minsta lokala emulator

Den första implementationen behöver bara:

1. servera ett test-CA-certifikat;
2. returnera det statiska lokala provisioningkontraktet;
3. ta emot TLS/MQTT CONNECT på 8883 och kontrollera exakt client ID/username;
4. acceptera adapterns prenumeration på direct methods;
5. logga och avkoda uplink utan att skicka något kommando;
6. bevisa med paketspårning att ingen trafik lämnar test-LAN.

Först när den fasen är stabil får EutherPump publicera ett syntetiskt,
ofarligt testmeddelande. `CMD_FCU_TO_AC` och fysisk styrning förblir avstängda
tills target-ID, state-hex och readback har verifierats mot den riktiga pumpen.

## Kvar att mäta fysiskt

- Exakt setup-JSON som den installerade firmwareversionen 4.4.00 accepterar.
- Om certifikathämtningen använder HTTP eller HTTPS för vald port.
- Hostname/SNI-kontrollen i 4.4.00 jämfört med analyserade 5.0.00.
- Första uplinkens exakta body och QoS.
- Hur adaptern beter sig om lokal broker eller DNS är nere vid omstart.
- En fullständigt verifierad återställning till nuvarande originalkonfiguration.
