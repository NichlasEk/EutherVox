# Beta 27: Ebbas serviceräknare

Version 0.19.0-beta.27, versionCode 51. Under robotpanelen finns
Underhåll och serviceräknare med separata återställningsknappar för huvudborste,
sidoborste och filter. Varje knapp öppnar en Ja/Nej-dialog och påverkar bara vald
del. Endast online, stillastående/laddande robot med tillgänglig styrning kan
bekräftas; servern kontrollerar även driftläget på nytt. Förklaringen skiljer
serviceintervall från faktiskt slitage. Inga återställningar via röstkommandon.

Bekräftelse krävs både i gateway och EutherWash API, utöver befintlig autentisering.
En accepterad återställning följs av befintlig statusavläsning; ingen optimistisk
ändring av procentvärden. Ingen verklig återställning kördes under utvecklingstesterna.

Validering: 184 gatewaytester, 51 Android-enhetstester och debugbygge passerade.
EutherWash: 125 tester. APK har samma signeringscertifikat som publicerade beta26.
SHA-256: 19ec3c92baa869800565620d4b9c13142548eb78d9fb181b15beb926d0d7e804.
Användaren installerade beta 27 och bekräftade därefter att alla tre
återställningarna fungerade på Ebba. Filter uppdaterades först; huvudborste
och sidoborste tog längre tid att visa resultat. Ingen exakt fördröjning mättes.
