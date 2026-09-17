# Automatisk verifierad YouTube-hämtare

Installerat 2026-09-17 på .88, där EutherVox-gatewayen körs.

## Beteende

`euthervox-youtube-update.timer` kontrollerar stabila yt-dlp-utgåvor på PyPI
söndagar 04:30–05:00 lokal tid. `Persistent=true` innebär att missade körningar
kan tas igen när datorn startar. Ingen musik spelas upp och roboten kontaktas
inte av underhållstestet.

En ny version installeras i en egen virtualenv under
`state/youtube-runtime/releases/`. Resten av gatewayens beroenden och repoets
låsta basmiljö ändras inte. Bara officiella stabila datumversioner installeras;
nightly/master används inte. Installerade beroenden sparas i kandidatens
`requirements.txt` tillsammans med `release.json`.

Två provvideor hämtas: Wagner `EUrvY1XggAI` och Dinosauriernas alfabet
`ouQmlz3I3v8`. Deras signerade ljudadresser skickas över SSH till .186, där
samma ffmpeg-parametrar som EutherWash avkodar två sekunder till minnet.
Båda måste ge exitkod 0 och exakt 64 000 PCM-byte. Det sker ingen
högtalaruppspelning. Lokal avkodning på .88 gav 403 för ett av proven trots
att samma prov gick på .186; därför används den faktiska avkodningsvärden.

Först efter godkänt test flyttas `current` atomiskt till kandidaten och den
föregående miljön sparas som `previous`. Gatewayen startar en separat
hämtarprocess för varje begäran och använder en konkret sökväg till den
aktuella miljön. Ett versionsbyte kräver därför ingen gateway-omstart och
påverkar inte en redan pågående låt. Endast den första aktiveringen av denna
integration krävde omstart.

Om installation, nätverk eller prov misslyckas sker ingen aktivering. En
underkänd kandidat tas bort och befintlig miljö lämnas kvar. Även när ingen
ny version finns provkörs den befintliga versionen. Status skrivs till
`state/youtube-runtime/status.json`; servicefel syns i systemd/journalen.
Detta garanterar inte att alla YouTube-länkar alltid fungerar. Om YouTube
ändrar något innan en fungerande stabil utgåva finns kan man fortfarande
behöva vänta eller ingripa manuellt.

## Installation och beroenden

Deployfilerna är `deploy/euthervox-youtube-update.service`, `.timer` och
`deploy/euthervox-youtube-runtime.conf`. Den sista installeras som
`~/.config/systemd/user/euthervox-gateway.service.d/youtube-runtime.conf`.
Gatewayens `EUTHERVOX_YOUTUBE_RUNTIME` pekar på
`/home/nichlas/EutherVox/state/youtube-runtime/current`.

Uppdateringstjänsten kräver `uv`, Deno, nätåtkomst till PyPI/YouTube och SSH
till .186 via aliaset `euther-server`. Nuvarande värd använder den befintliga
privata askpass-hjälparen under
`EutherWash/.private/cloud-independence-20260915/server-askpass`.
Själva hemligheten ligger inte i deployfilerna eller Git. Om åtkomsten ändras
underkänns underhållstestet i stället för att en oprövad version aktiveras.

Basversionen i EutherVox `.venv` och `uv.lock` finns kvar för återställning.
Automatiska uppdateringar ändrar inte Git och pushar inga commits. Runtime-
katalogen och dess rapporter är gitignorerade.

## Drift och återställning

```sh
systemctl --user list-timers euthervox-youtube-update.timer
systemctl --user start euthervox-youtube-update.service
cat /home/nichlas/EutherVox/state/youtube-runtime/status.json
journalctl --user -u euthervox-youtube-update.service -n 30
```

Pausa automatiken med `systemctl --user disable --now euthervox-youtube-update.timer`.
Detta påverkar inte den aktiva hämtaren.

Efter minst ett versionsbyte kan föregående miljö provköras och återställas:

```sh
cd /home/nichlas/EutherVox
EUTHERVOX_YOUTUBE_SMOKE_SSH=euther-server \
SSH_ASKPASS=/home/nichlas/EutherWash/.private/cloud-independence-20260915/server-askpass \
SSH_ASKPASS_REQUIRE=force DISPLAY=euther \
.venv/bin/python scripts/update_youtube_runtime.py --rollback
```

För att återgå helt till repoets basmiljö: pausa timern, ta bort endast
`youtube-runtime.conf`-drop-in-filen, kör `systemctl --user daemon-reload` och
starta om `euthervox-gateway.service`. Radera inte återställningsmiljöerna.

## Verifierat

Första kandidaten 2026.8.19 aktiverades efter godkända serverprov. Därefter
kördes tjänsten via systemd med sina riktiga miljövariabler och rapporterade
`current_verified`. Gatewayen är aktiv. 78 tester passerade, inklusive
kontroller att underkända kandidater inte ersätter den fungerande miljön,
att tidigare miljö bevaras och att signerade adresser inte skrivs i felstatus.
