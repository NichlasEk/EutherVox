# EutherVox WebSocket protocol v1

En WebSocket-anslutning motsvarar exakt en session. JSON-textframes är kontrollplanet och binära frames är ljudplanet. Alla UUID:n är opaka strängar.

## Tillstånd och binär frame-koppling

```text
CONNECTED --session.start--> READY --audio.start(U)--> RECORDING(U)
    RECORDING(U) --binary PCM* + audio.end(U)--> PROCESSING(U)
    PROCESSING(U) --stt/text + tts.start(U)--> SPEAKING(U)
    SPEAKING(U) --binary PCM* + tts.end(U)--> READY
    PROCESSING(U) --action.request(U)--> READY
```

Det finns ingen identifierare inuti en binär frame i version 1. Kopplingen är tillståndsbaserad och entydig eftersom endast ett yttrande får vara aktivt per anslutning:

- Binära frames från klienten mellan `audio.start(U)` och `audio.end(U)` är mikrofonljud för yttrande `U`.
- Binära frames från servern mellan `tts.start(U)` och `tts.end(U)` är TTS-ljud för yttrande `U`.
- Binärt klientljud i andra tillstånd ger `UNEXPECTED_AUDIO`.
- Ett nytt `audio.start` innan föregående yttrande är avslutat ger `SESSION_BUSY`.
- `response.cancel(U)` avbryter pipeline/uppspelning och återför servern till `READY`.

Detta lämpar sig även för Raspberry Pi-noder: varje nod håller sin egen anslutning och behöver inte multiplexera nod- eller yttrande-ID i ljudframes. En framtida multiplexad version måste lägga till ett binärt frame-headerfält och höja protokollversionen.

## Ljud

Klient till server:

```toml
codec = "pcm_s16le"
sample_rate = 16000
channels = 1
frame_ms = 20
frame_bytes = 640
```

Server till klient annonseras i `tts.start`. Prototypen använder mono `pcm_s16le` vid 24000 Hz och skickar normalt 20 ms/960 byte per frame. Klienten startar AudioTrack efter cirka 120 ms eller när en kort ström tar slut.

## Kontrollmeddelanden

Meddelandena och fälten följer exemplen i arbetsuppdraget:

- Klient: `session.start`, `audio.start`, `audio.end`, `response.cancel`, `action.confirm`, `action.result`.
- Server: `session.ready`, `stt.partial`, `stt.final`, `assistant.text.delta`, `assistant.text.final`, `tts.start`, `tts.end`, `action.request`, `action.status`, `action.completed`, `response.cancelled`, `error`.

`session.start.input_audio` valideras innan `session.ready`. Alla yttrandemeddelanden ska använda samma `utterance_id` som aktiverades med `audio.start`.

Fel har formen:

```json
{"type":"error","code":"STT_FAILED","message":"Speech recognition failed","recoverable":true}
```

Vid ett återhämtningsbart fel kan anslutningen behållas och klienten återgå till Idle. Protokollversion- eller ljudformatsfel är inte återhämtningsbara och servern stänger anslutningen med WebSocket-kod 1002.

## Enhetsåtgärder

Åtgärder använder samma sessionsanslutning men ligger utanför det binära ljudplanet. Servern skickar bara namn och argument ur en fast vitlista. Klienten måste själv kontrollera åtgärdsnamn, leverantör, mål och om bekräftelse krävs innan den gör något.

Första åtgärden spelar musik på noden som tog emot yttrandet:

```json
{
  "type": "action.request",
  "action_id": "generated-id",
  "utterance_id": "generated-id",
  "name": "media.play",
  "target": {"kind": "node", "node_name": "android-phone"},
  "arguments": {
    "provider": "youtube_music",
    "query": "något mörkt och lugnt"
  },
  "requires_confirmation": false
}
```

Noden rapporterar resultatet separat:

```json
{
  "type": "action.result",
  "action_id": "generated-id",
  "utterance_id": "generated-id",
  "status": "completed",
  "message": "Skickade sökningen till YouTube Music"
}
```

`status` är `completed`, `failed` eller `rejected`. Ett `action_id` är giltigt endast om servern har skickat motsvarande begäran på samma anslutning. I version 1 är målet alltid den anslutna noden själv. Framtida rumsdirigering ska välja en annan aktiv nodanslutning på servern; en telefon får inte låtsas vara en annan nod bara genom att ändra `target`.

En beständig åtgärd, exempelvis `playlist.create`, har alltid `requires_confirmation: true`. Klienten visar argumenten men kör ingenting. Godkännande skickas som `{"type":"action.confirm","action_id":"..."}`; avslag skickas som `action.result` med `status: rejected`. Servern rapporterar arbetet med `action.status` och exakt ett avslutande `action.completed`.

`playlist.create` skapar alltid först en privat lokal lista bunden till den verifierade identiteten från `X-Euther-User`. Argumentets provider är därför `euthervox`. Om användarens OAuth-koppling finns skapar servern även en privat YouTube-spegling och skickar `media.open` med dess `https://music.youtube.com/`-länk. Utan OAuth skickas i stället `media.play` med den lokala listans fråga; den lokala listan bevaras och inget Google-konto ändras.

## Ordning och backpressure

Kontroll- och binärframes använder samma WebSocket och bevarar därmed ordningen. Klientens ljudtråd väntar aldrig på nätverket: 20 ms-block läggs i en liten begränsad kö. Om kön är full tappas blocket och räknaren visas i UI. Serverns utgående TTS iterator inväntar varje `send`, vilket ger naturlig backpressure utan att hela svaret buffras.

Rått ljud ska inte loggas eller sparas. Textloggning styrs av `server.text_logging`; prototypens strukturerade standardlogg innehåller session, yttrande, byte-/frameantal och tider, inte ljuddata.
