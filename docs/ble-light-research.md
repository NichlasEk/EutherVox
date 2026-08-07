# BLE-ljus: identifiering före styrning

> Uppdatering: kontrollrarna på bilden visade sig vara Wi-Fi-modellen
> `AK001-ZJ200`. Huvudspåret finns nu i [magic-home-wifi.md](magic-home-wifi.md).
> BLE-labbet behålls bara för annan framtida hårdvara.

`Magic Home` är ett app-/försäljningsnamn och identifierar inte ensamt ett
protokoll. Första Android-slicen är därför diagnostisk: skanna, välj enhet, läs
GATT-fingeravtryck och kopiera resultatet. Den skriver inga bytes till slingan.

## Kända familjer

- LED BLE/Triones brukar annonsera namn som `LEDnet`, `BLE-LED`, `LEDBLE`,
  `LEDBlue`, `Triones`, `Dream` eller `QHM`. En vanlig GATT-familj använder
  service `FFD5` och skrivkarakteristik `FFD9`. Den underhållna Apache-2.0-
  implementationen finns i <https://github.com/Bluetooth-Devices/led-ble> och
  används av Home Assistants lokala LED BLE-integration:
  <https://www.home-assistant.io/integrations/led_ble>.
- Surplife/Magic Home BLE-varianter har observerats med service `F000`, skrivning
  på `FF01` och notifieringar på `FF02`. Den familjen har annan framing och får
  inte styras med Triones-bytes.
- Klassiska Magic Home/Magic Hue-kontrollers kan i stället vara Wi-Fi-enheter som
  styrs lokalt över TCP port 5577. En MIT-licensierad referens finns i
  <https://github.com/MoonLiightz/magic-home>.

## Säker infasning

1. Android visar alla BLE-enheter men sorterar sannolika LED-kontrollers först.
2. `Undersök` ansluter, kör service discovery och visar UUID samt egenskaper som
   `write`, `write-no-response` och `notify`.
3. En drivrutin aktiveras endast när dess service och skrivkarakteristik matchar.
4. Första skrivtestet blir av/på och fasta RGB-färger med tydlig manuell kontroll.
5. Därefter läggs ljusstyrka och en allowlist med verifierade mönster till.
6. Samma validerade kommandon exponeras sist som MCP-verktyg. Modellen får välja
   logiskt ljusnamn, färg, ljusstyrka och mönster, aldrig MAC-adress, UUID eller
   råa bytes.

Androids BLE-anslutning stannar i telefonen. Senare rumsnoder får egna lokala
drivrutiner bakom samma logiska verktygsgräns, så servern behöver inte routa
Bluetooth-trafik över internet.
