# Magic Home Wi-Fi i EutherVox

## Verifierad hårdvara

Två lokala enheter har identifierats som `AK001-ZJ200`. De svarar på:

- UDP `48899` med discovery-frågan `HF-A11ASSISTHREAD`
- TCP `5577` med Magic Home-statusfrågan `81 8a 8b 96`

Statuskontrollen är läsande. Svaren bekräftade den klassiska Magic Home-
paketfamiljen innan styrknappar lades till i Android-appen.

## Befintlig modul

1. Anslut telefonen till samma lokala Wi-Fi som modulen.
2. Öppna `Ljus` i EutherVox.
3. Tryck `Sök Wi-Fi`.
4. Modellen, IP-adressen, MAC-adressen och status visas.
5. Prova först `Tänd` och `Släck`, därefter en grundfärg.

Kommandona går direkt mellan telefonen och modulen på det lokala nätet. De går
inte genom EutherVox Gateway.

## Ny modul

1. Återställ modulen enligt dess instruktion tills den skapar ett tillfälligt
   Wi-Fi vars namn normalt börjar med `LEDnet`.
2. Öppna `Ljus` och `Lägg till ny`.
3. Använd knappen som öppnar Androids Wi-Fi-panel och anslut manuellt till
   modulens nät.
4. Gå tillbaka till EutherVox och ange hemmets 2,4 GHz-SSID och WPA2-lösenord.
5. Tryck `Konfigurera modul`.
6. När modulen startat om, återanslut telefonen till hemmets Wi-Fi och tryck
   `Sök Wi-Fi`.

SSID och lösenord hålls bara i installationsdialogens minne. Lösenordsfältet
töms när installationen avslutas eller avbryts. Uppgifterna skrivs inte till
SharedPreferences, logg eller server.

Appen upptäcker själv modulens adress i AP-läget med
`HF-A11ASSISTHREAD`, bekräftar övergången till nätverks-AT-läge och skickar
därefter stationsläge, SSID, WPA2-nyckel och omstart. Den förutsätter alltså
inte en hårdkodad `10.10.x.x`-adress.

## Säkerhetsgräns

Provisionering ska vara en fysisk, lokal telefonfunktion och ska inte exponeras
som röstverktyg. Ett kommande MCP-lager får endast styra namngivna, i förväg
godkända enheter och färger/effekter. Det får inte ta emot IP-adresser, råa
protokollpaket eller Wi-Fi-hemligheter från språkmodellen.

## Kända begränsningar

- Första betan stöder WPA2-PSK/AES och 2,4 GHz. WPA3-only är inte implementerat.
- Installation måste provas fysiskt med en återställd reserv-/ny modul.
- RGB-knapparna använder den verifierade klassiska paketfamiljen. Vita kanaler
  och inbyggda animationsmönster varierar mellan hårdvarurevisioner och läggs
  inte till innan de har provats mot dessa exemplar.
- LAN-discovery kan blockeras av gästnät eller accesspunkter med klientisolering.
