# Vox beta.29 — behåll skrivfokus och ta bort avståndsspärren

Användaren provade budbäraren och bekräftade att den fungerar rätt bra.
Statusfrågan var tredje sekund satte emellertid `deliveryBusy=true`, vilket
inaktiverade textfältet och tog bort fokus/tangentbord under pågående skrivning.

`current`-pollning låser nu inte formuläret. Explicita kommandon och ett aktivt
robotuppdrag behåller sina vanliga spärrar. Pollning sker endast när panelen
är öppen och appen i fokus.

Femmeterskontrollen i worker och motsvarande instruktion i appen är borttagna
på användarens begäran. Kartidentitet, fri golvyta, färsk position, stoppvakt
samt tidsgränser för utkörning/hemgång kvarstår. Ett långt eller blockerat
uppdrag kan därför fortfarande avbrytas när tidsgränsen nås.

Version: **0.19.0-beta.29 / versionCode 53**.
Regressionsprov kör hela workerflödet med mål sju meter från dockan; det
accepteras, stannar för ljud och skickar hem först efter uppspelning.
Inga nya fysiska rörelsekommandon skickades under ändringen.

Publiceras via EutherOxide Apps. Tidigare beta.28 och beta.27 behålls på sina
versionsspecifika nedladdningsadresser.

Validering: nio backendtester inklusive sju meters mål, Android JVM-tester
och assembleDebug passerar. Explicit användaråtgärd kan gå före en pågående
statusfråga; dess busy-läge rensas inte av statusfrågans äldre svar.
APK SHA256: `a50bcd83b534fec2987c972d453173522159a2397907b88817630c1268ffb051`.
