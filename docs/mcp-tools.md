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
                             YouTube / Cast / Android node
```

Den deterministiska vägen ligger först eftersom vanliga kommandon då inte behöver ett extra modellanrop. Verktygsplaneraren anropas endast för yttranden med musik- eller rumsindikatorer. Om Ollama inte väljer exakt ett verktyg fortsätter yttrandet till den vanliga figurresponsen.

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

## Säkerhetsgräns

- Endast registrerade verktygsnamn accepteras.
- JSON-argument har en fast allowlist och okända fält avvisas.
- Text normaliseras, måste vara 1–160 tecken och kan inte bära egen användaridentitet.
- Rum måste finnas i gatewayens uttryckliga Cast-konfiguration.
- Modellen kan inte ange IP, port, UUID, URL, filväg, shellkommando eller EutherOxide-användare.
- MCP stdio-servern skapar förslag men har ingen fristående exekveringsbehörighet.

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
