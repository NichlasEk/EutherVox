from __future__ import annotations

import argparse
from dataclasses import asdict

from mcp.server import MCPServer

from .cast import CastService
from .config import load_config
from .tools import EutherVoxToolRegistry
from .wikipedia import WikipediaService


def build_mcp_server(registry: EutherVoxToolRegistry, wikipedia: WikipediaService | None = None) -> MCPServer:
    server = MCPServer(
        "EutherVox",
        description="Säkra verktyg för EutherVox musik, Wikipedia, privata spellistor och konfigurerade rumsenheter.",
        instructions="Åtgärdsverktygen skapar validerade förslag. Wikipedia är skrivskyddat. Spellistor kräver alltid användarbekräftelse.",
    )

    @server.tool()
    def cast_list_targets() -> list[dict[str, str]]:
        """Lista tillåtna Cast-rum utan att lämna ut IP-adresser eller andra anslutningsdetaljer."""
        return registry.list_cast_targets()

    @server.tool()
    def music_play(query: str, output_room: str = "") -> dict:
        """Skapa ett validerat förslag om att spela musik på telefonen eller i ett tillåtet rum."""
        return asdict(registry.create_action("music_play", {"query": query, "output_room": output_room}, "mcp-client"))

    @server.tool()
    def playlist_create(description: str, output_room: str = "") -> dict:
        """Skapa ett validerat spellisteförslag som alltid kräver bekräftelse innan det utförs."""
        return asdict(
            registry.create_action(
                "playlist_create",
                {"description": description, "output_room": output_room},
                "mcp-client",
            )
        )

    @server.tool()
    async def wikipedia_lookup(query: str) -> dict[str, str]:
        """Hämta titel, artikelinledning och käll-URL från svenska Wikipedia utan att redigera något."""
        if wikipedia is None:
            raise RuntimeError("Wikipedia-verktyget är inte konfigurerat")
        article = await wikipedia.lookup(query)
        return {"title": article.title, "extract": article.extract, "url": article.url}

    return server


def cli() -> None:
    parser = argparse.ArgumentParser(description="EutherVox MCP server over stdio")
    parser.add_argument("--config", default="config.toml")
    args = parser.parse_args()
    config = load_config(args.config)
    registry = EutherVoxToolRegistry(CastService(config.cast_settings))
    wikipedia = WikipediaService(config.wikipedia_settings)
    build_mcp_server(registry, wikipedia).run(transport="stdio")


if __name__ == "__main__":
    cli()
