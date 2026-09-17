# System Regis – EutherVox beta33

2026-09-17. Android versionCode 57, versionName 0.19.0-beta.33.

## Användning

Öppna Röst → Inställningar → Utseende. Välj **System Regis – Stormakt 2030**
och **Spara och stäng**. Klassiskt – ljust finns kvar. Valet gäller användaren
som anges i inställningarna. Användarbyte laddar det kontots lokala val;
serverns val hämtas när autentiserad anslutning är klar.

System Regis använder mörkblå bakgrund (#071523), guld (#D4B86A), ljusblått
(#8BCDE8), ljus text och stramare kort. Miljöbilder ersätts av en typografisk
System Regis-panel. Inställningar, apparatkort, budbärare och musik använder
samma Material-färger. Kartpixlar och lampornas faktiska färgval behålls.
Systemfälten har ljusa symboler; safe-area-insets hindrar innehåll från att
hamna bakom statusfält och navigeringsfält.

## Sparning och kontogräns

Gatewayen lagrar en fil per autentiserad användare i
`/home/nichlas/EutherVox/state/user-preferences/<sha256>.toml` på .88.
Filnamnet är SHA-256 av normaliserat användarnamn (trim + casefold).

```toml
[appearance]
theme = "system_regis"
```

Alternativet heter `classic`. Servern använder den autentiserade identiteten,
aldrig ett användarnamn från ändringsmeddelandet. Skrivningen sker atomiskt
via tempfile, fsync och replace; filer skapas med 0600. Katalogen är privat
runtime och ska säkerhetskopieras som konfiguration, inte checkas in i Git.

`appearance.set` validerar val och inloggning. `appearance.saved` kvitterar
skrivningen; `session.ready.appearance_theme` förmedlar sparat val. Gamla
klienter fortsätter fungera. Trasig/oläsbar inställningsfil ska inte hindra en
röstsession; felet loggas utan hemligheter och telefonens cache behålls.

Telefonen har en lokal cache per användare i privat SharedPreferences.
Den läses före första Compose-renderingen, utan nätverksväntan. Ett lokalt
val sparas omedelbart och köas tills servern kvitterar det. En äldre
kvittens får inte skriva över ett nyare val. Andra telefoner hämtar valet vid
nästa anslutning; aktiv push till redan anslutna telefoner ingår inte.

## Uppstart utan vit startyta

Manifestets fönstertema är mörkt från början, även innan appkod kan läsa
användarinställningar. `windowBackground`, status- och navigeringsfält är
mörkblå. Android 12+ har dessutom explicit mörk
`windowSplashScreenBackground` och en diskret guld/ljusblå startikon.
Den neutrala startskärmen är avsiktligt mörk även i klassiskt läge;
klassiska gränssnittet blir ljust först när det har laddats.

Detta täcker appens egna startytor. Telefonens launcher, tangentbord och
Androids egna behörighetsdialoger styrs separat av systemet.
Se [Androids splash screen-dokumentation](https://developer.android.com/develop/ui/views/launch/splash-screen).

## Verifiering och distribution

- Android debugbygge och JVM-tester passerade.
- Gatewaytester för kontoisolering, nekad anonym skrivning, ogiltigt tema,
  TOML/filrättigheter och återanslutning passerade, tillsammans med befintliga
  session- och EutherWash-gatewaytester.
- Emulator: sparat System Regis-val lästes vid kallstart. Bildsekvensen visade
  mörkblå startyta och därefter mörkt gränssnitt/dialog utan vit helskärm i de
  fångade rutorna. Klassisk färgpalett finns kvar; fysisk telefon behöver
  fortfarande provas av användaren.
- APK SHA-256:
  `2a5aed58da670e2cc672a28669345855d86f8f694a118be442fd8e2f6f1c7344`.
- EutherOxide publicerar beta33 genom Apps och `/downloads/EutherVox.apk`;
  äldre versionerade nedladdningar behålls.
