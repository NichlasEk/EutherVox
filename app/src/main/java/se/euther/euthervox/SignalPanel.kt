package se.euther.euthervox

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import se.euther.euthervox.app.VoiceController
import se.euther.euthervox.app.VoiceUiState
import se.euther.euthervox.app.VoiceStatus
import kotlinx.coroutines.delay

private fun JsonObject.signalText(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString ?: ""

@Composable
fun SignalPanel(state: VoiceUiState, controller: VoiceController, hasMicrophonePermission: Boolean, requestMicrophonePermission: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { controller.requestSignal(); delay(10000) } }
    var question by remember { mutableStateOf("") }
    val data = state.signalData
    val report = data?.get("report")?.takeIf { it.isJsonObject }?.asJsonObject
    val job = data?.get("job")?.takeIf { it.isJsonObject }?.asJsonObject
    val working = job?.signalText("state") in listOf("collecting", "writing")
    val discussing = data?.get("discussing")?.asBoolean == true
    val uri = LocalUriHandler.current
    val sources = report?.getAsJsonArray("sources")?.map { it.asJsonObject } ?: emptyList()
    val reportId = report?.signalText("id").orEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("EutherSignal", style = MaterialTheme.typography.headlineMedium)
        Text("Vad blev möjligt idag?", style = MaterialTheme.typography.titleLarge)
        Text("AI 25 % · Digital frihet 20 % · Retro 20 % · Hemmalabb 15 % · Vetenskap 10 % · Apotek 10 %", style = MaterialTheme.typography.bodySmall)
        Text("Urval efter dina intressen. Påståenden, belägg och invändningar hålls isär.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.requestSignal("collect") }, enabled = !state.signalBusy && !working) { Text("Hämta nyheter") }
            if (working) OutlinedButton(onClick = { controller.requestSignal("cancel") }, enabled = !state.signalBusy) { Text("Avbryt") }
        }
        job?.signalText("message")?.takeIf { it.isNotBlank() }?.let { Text(it) }
        if (working) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Den lokala modellen kan behöva några minuter. Du kan lämna avsnittet medan rapporten skrivs.", style = MaterialTheme.typography.bodySmall) }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (report == null) {
            if (!working) Text("Anslut under Röst och välj Hämta nyheter för din första rapport.")
        } else {
            val stamp = runCatching { java.time.Instant.ofEpochSecond(report.get("created_at").asDouble.toLong()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrDefault("")
            Text("Rapport $stamp", style = MaterialTheme.typography.labelLarge)
            Text(report.signalText("introduction"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.speakSignal(reportId) }, enabled = state.canTalk && !working) { Text("Lyssna på briefingen") }
            }
            OutlinedButton(onClick = { controller.requestSignal(if (discussing) "leave" else "discuss", reportId) }, enabled = !state.signalBusy && state.canTalk) { Text(if (discussing) "Lämna rapportsamtalet" else "Prata om rapporten") }
            if (discussing) {
                Text("Fråga om denna rapport, exempelvis: Vad är haken med den andra nyheten? Säg Lämna Signal för vanligt samtal.", style = MaterialTheme.typography.bodySmall)
                if (!hasMicrophonePermission) OutlinedButton(onClick = requestMicrophonePermission) { Text("Tillåt mikrofon") }
                Button(onClick = { if (state.microphoneActive) controller.stopTalking() else controller.startTalking() }, enabled = hasMicrophonePermission && (state.canTalk || state.microphoneActive) && !working && !state.conversationActive) { Text(if (state.microphoneActive) "Skicka frågan" else "Fråga med rösten") }
            }
            if (state.status == VoiceStatus.Processing) Text("Den lokala modellen förbereder svaret. Det kan ta några minuter när minnet delas med andra AI-tjänster.", style = MaterialTheme.typography.bodySmall)
            if (state.status in listOf(VoiceStatus.Processing, VoiceStatus.Speaking)) OutlinedButton(onClick = controller::cancelResponse) { Text("Avbryt svaret") }
            OutlinedTextField(value = question, onValueChange = { question = it.take(1200) }, label = { Text("Fråga om rapporten") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { controller.speakSignal(reportId, question); question = "" }, enabled = state.canTalk && !working && question.isNotBlank()) { Text("Fråga och lyssna") }
            data?.get("answer")?.takeIf { it.isJsonObject }?.asJsonObject?.let { answer ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("Signals svar", style = MaterialTheme.typography.titleMedium)
                    Text(answer.signalText("answer"))
                    val ids = answer.getAsJsonArray("source_ids")?.map { it.asString } ?: emptyList()
                    sources.filter { it.signalText("id") in ids }.forEach { source -> TextButton(onClick = { uri.openUri(source.signalText("url")) }) { Text(source.signalText("source")) } }
                } }
            }
            val failures = report.getAsJsonArray("source_health")?.count { !it.asJsonObject.get("ok").asBoolean } ?: 0
            if (failures > 0) Text("$failures källor gick inte att hämta. Rapporten täcker tillgängligt underlag.", color = MaterialTheme.colorScheme.error)
            report.getAsJsonArray("items")?.forEachIndexed { index, element ->
                val item = element.asJsonObject
                val source = sources.firstOrNull { it.signalText("id") == item.signalText("source_id") }
                val section = when (item.signalText("section")) { "build" -> "Det här kan du bygga"; "freedom" -> "Frihetsradarn"; "discovery" -> "Dagens märkliga upptäckt"; else -> "Dagens viktigaste" }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(section, style = MaterialTheme.typography.labelLarge)
                    Text("${index + 1}. ${item.signalText("headline")}", style = MaterialTheme.typography.titleLarge)
                    Text(item.signalText("summary"))
                    Text(item.signalText("why_it_matters"))
                    Text("Verklighetskontroll: ${item.signalText("caveat")}", style = MaterialTheme.typography.bodyMedium)
                    if (source != null) {
                        val date = runCatching { java.time.Instant.ofEpochSecond(source.get("published_at").asDouble.toLong()).toString().take(10) }.getOrDefault("")
                        val dateLabel = if (source.signalText("date_basis") == "updated") "Uppdaterad" else "Publicerad"
                        val scope = if (source.signalText("evidence_scope") == "feed_excerpt") "Nyhetsflödets utdrag" else "Nyhetsflöde och artikelutdrag"
                        val kind = when (source.signalText("kind")) { "developer" -> "Utvecklarkälla"; "advocacy" -> "Intresseorganisation"; "authority" -> "Myndighet/region"; "research_publication" -> "Forskningspublikation"; else -> "Redaktionell källa" }
                        Text("${source.signalText("source")} · $kind · $dateLabel $date · $scope", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { uri.openUri(source.signalText("url")) }) { Text("Läs originalkällan") }
                    }
                } }
            }
            if (report.signalText("experiment").isNotBlank()) {
                Text("En idé att prova", style = MaterialTheme.typography.titleLarge)
                Text(report.signalText("experiment").replace(Regex("\\s*(?:experiment_)?source_ids\\s*:\\s*\\[[^\\]]*\\]\\.?\\s*$", RegexOption.IGNORE_CASE), ""))
                Text("Förslag från modellen, inte en verifierad nyhet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
