# EutherVox MCP tools

EutherVox använder ett gemensamt verktygsregister för två protokollvägar:

1. Gatewayen översätter registret till Ollamas `tools`-format och låter Qwen välja verktyg för naturliga musikönskemål som snabbtolkaren missar.
2. `euthervox-mcp` exponerar samma register som en MCP 2-server över stdio.

Det gör verktygssemantiken identisk för Android-appen, framtida Raspberry Pi-noder och externa MCP-värdar.

## Flöde

```text
STT text
  ├─ deterministic ActionPlanner ─────────────┐
  └─ Ollama tool call ── ToolRegistry ────────┤
                                              v
                                    validated DeviceAction
                                              |
                         authenticated VoiceSession execution
                                              |
                         Wikipedia / YouTube / Cast / Magic Home / Android node
```

Den deterministiska vägen ligger först eftersom vanliga kommandon då inte behöver ett extra modellanrop. Verktygsplaneraren anropas för yttranden med musik-, rums- eller ljusindikatorer. Om Ollama inte väljer exakt ett verktyg fortsätter yttrandet till den vanliga figurresponsen.

## Verktyg

### `cast_list_targets`

Returnerar `room`, `display_name` och `model` för explicit konfigurerade Cast-mottagare. IP-adress, port och UUID exponeras inte.

### `music_play`

Argument:

```json
{"query":"mörk cyberpunk","output_room":"köket"}
```

Ger en intern `media.play`. `output_room` är valfritt; utan rum skickas åtgärden till den aktuella telefon-/nodsessionen.

### `playlist_create`

Argument:

```json
{"description":"mörk svensk synth","output_room":"köket"}
```

Ger en intern `playlist.create` med `requires_confirmation=true`. Den autentiserade användaren måste bekräfta i appen innan TOML-listan eller YouTube-speglingen skapas.

### `wikipedia_lookup`

Argument i den interna modellvägen:

```json
{"query":"Skinnskatteberg","mode":"summary"}
```

`mode` är `summary` eller `introduction`. Gatewayen hämtar endast artikelinledningen från den uttryckligen konfigurerade svenska MediaWiki-API-adressen. Vid `summary` får den lokala modellen källtexten och måste sammanfatta enbart den; vid `introduction` läses inledningen upp utan omskrivning. Appens sluttext innehåller artikelns titel och URL, men URL:n skickas inte till TTS.

Den fristående MCP-serverns `wikipedia_lookup` returnerar `title`, `extract` och `url` direkt. Den är skrivskyddad och kan varken redigera Wikipedia eller välja en annan värd genom verktygsargument.

### `lights_list`, `light_set` och `light_effect`

`lights_list` returnerar bara `id`, namn, rum och modell från serverns
`state/lights.toml`. Nätverksadresser lämnas aldrig till modellen eller en
MCP-klient.

```json
{"target":"köket","color":"#6B20A8","brightness":30}
```

`light_set` accepterar ett registrerat lampnamn eller rum, valfri `power`, en
validerad `#RRGGBB`-färg och ljusstyrka 1–100. `light_effect` accepterar samma
mål, ett mönster ur verktygets fasta enum och hastighet 1–100. MCP-anropen
verkställs av gatewayens Magic Home-driver; Ollama-vägen skapar motsvarande
validerade `lights.set`/`lights.effect` och verkställs i den autentiserade
röstsessionen.

## Säkerhetsgräns

- Endast registrerade verktygsnamn accepteras.
- JSON-argument har en fast allowlist och okända fält avvisas.
- Text normaliseras, måste vara 1–160 tecken och kan inte bära egen användaridentitet.
- Rum måste finnas i gatewayens uttryckliga Cast-konfiguration.
- Modellen kan inte ange IP, port, UUID, URL, filväg, shellkommando eller EutherOxide-användare.
- Wikipedia-värden anges endast i serverns TOML; modellen får bara ange söktext och uppläsningsläge.
- Lampornas IP och MAC finns endast i server-TOML och kan inte anges som verktygsargument.
- Wi-Fi-provisionering och lösenord exponeras aldrig genom MCP.

En framtida Streamable HTTP-transport ska ligga bakom EutherOxides autentisering. Den måste översätta verifierad identitet till en kortlivad sessionskontext och får inte acceptera användaridentitet som verktygsargument.

## Klientkonfiguration

Generisk stdio-konfiguration för en MCP-värd:

```json
{
  "mcpServers": {
    "euthervox": {
      "command": "uv",
      "args": [
        "--directory",
        "/path/to/EutherVox",
        "run",
        "euthervox-mcp",
        "--config",
        "/path/to/EutherVox/config.real-beta.example.toml"
      ]
    }
  }
}
```

Det exakta konfigurationsformatet runt `mcpServers` bestäms av värden; kommandot och argumenten ovan är serverns stabila gräns.
