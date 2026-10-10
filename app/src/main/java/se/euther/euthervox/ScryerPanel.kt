package se.euther.euthervox

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import se.euther.euthervox.app.VoiceController
import se.euther.euthervox.app.VoiceUiState
import kotlinx.coroutines.delay

private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString ?: ""
private fun JsonObject.flag(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false

@Composable
fun ScryerPanel(state: VoiceUiState, controller: VoiceController) {
    LaunchedEffect(Unit) {
        while (true) { controller.requestScryer(); delay(60000) }
    }
    val report = state.scryerReports
    val available = report?.flag("available") == true
    val rows = report?.getAsJsonArray("reports")?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null } ?: emptyList()
    val inventory = report?.text("inventory_at").orEmpty()
    val stale = runCatching { java.time.Duration.between(java.time.OffsetDateTime.parse(inventory).toInstant(), java.time.Instant.now()).seconds.let { it >= 86400 || it < -60 } }.getOrDefault(true)
    val stopped = report?.flag("paused") == true || report?.flag("disabled") == true || report?.flag("observer_error") == true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scryer · problemöversikt", style = MaterialTheme.typography.headlineSmall)
        Text("Rapporter från inventeringen. Kvittering betyder att du har sett problemet, inte att det är åtgärdat.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { controller.requestScryer() }, enabled = !state.scryerBusy) { Text(if (state.scryerBusy) "Hämtar…" else "Uppdatera") }
        OutlinedButton(onClick = controller::speakScryer, enabled = state.canTalk) { Text("Läs upp lägesrapport") }
        Text("Säg till exempel: Scryer, rapportera. Siaren, säg mig läget. Eller: Orakel, ge mig din sanning.", style = MaterialTheme.typography.bodySmall)
        if (state.status == se.euther.euthervox.app.VoiceStatus.Speaking || state.status == se.euther.euthervox.app.VoiceStatus.Processing) {
            OutlinedButton(onClick = controller::cancelResponse) { Text("Avbryt uppläsning") }
        }
        if (!available) {
            Text(report?.text("error")?.ifBlank { null } ?: "Anslut till EutherVox för att hämta Scryers rapporter.")
        } else {
            Text("Inventering: $inventory", style = MaterialTheme.typography.bodySmall)
            if (stale || stopped) Text("Övervakningen är pausad eller underlaget är gammalt/ofullständigt. Detta är senast kända läge.", color = MaterialTheme.colorScheme.error)
            if (rows.size >= 64) Text("Visar högst 64 rapporter; listan kan vara ofullständig.")
            Text("${rows.count { it.text("state") == "open" }} öppna · ${rows.count { it.text("state") == "resolved" }} återhämtade", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) Text(if (stale || stopped) "Inga sparade rapporter. Aktuellt läge är inte bekräftat." else "Inga rapporterade problem i tillgängligt underlag.")
            rows.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.text("id"), style = MaterialTheme.typography.titleMedium)
                        val label = when (row.text("state")) { "open" -> "Rapporterat problem"; "resolved" -> "Återhämtat enligt inventeringen"; "pending" -> "Inväntar ytterligare observation"; else -> "Aktuellt läge okänt" }
                        Text("$label · ${row.text("status")}")
                        Text("Först sett: ${row.text("first_stamp")}\nSenaste felbelägg: ${row.text("last_bad_stamp")}\nSenaste underlag: ${row.text("stamp")}", style = MaterialTheme.typography.bodySmall)
                        val related = row.getAsJsonArray("related")?.joinToString { it.asString }.orEmpty()
                        if (related.isNotBlank()) Text("Närliggande beroenden: $related. Sambandet är inte en bekräftad felorsak.", style = MaterialTheme.typography.bodySmall)
                        if (row.flag("acknowledged")) Text("Kvitterad")
                        val until = row.get("snoozed_until")?.asDouble ?: 0.0
                        if (until > System.currentTimeMillis()) Text("Tystad till ${java.time.Instant.ofEpochMilli(until.toLong())}")
                        if (row.text("state") != "resolved") {
                            Text("Nästa steg: kontrollera tjänstens egen status och senaste ändring innan du vidtar åtgärder.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { controller.requestScryer("report-ack", row.text("id")) }, enabled = !state.scryerBusy && !row.flag("acknowledged")) { Text("Kvittera") }
                                OutlinedButton(onClick = { controller.requestScryer("report-snooze", row.text("id")) }, enabled = !state.scryerBusy) { Text("Tysta 1 tim") }
                            }
                        }
                    }
                }
            }
        }
    }
}
