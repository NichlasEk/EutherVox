package se.euther.euthervox

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import java.util.UUID
import se.euther.eutherbeam.EutherBeamPanel

@Composable
fun TvDevicesPanel(data: JsonObject?, busy: Boolean, onRequest: (String, JsonObject)->Unit) {
    var commands by remember { mutableStateOf(JsonArray()) }
    var captureId by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf(false) }
    var polling by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf("TV i vardagsrummet") }
    var label by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf(false) }
    var beam by remember { mutableStateOf(false) }
    var testId by remember { mutableStateOf("") }
    val latestBusy by rememberUpdatedState(busy)
    val latestRequest by rememberUpdatedState(onRequest)
    LaunchedEffect(Unit) { onRequest("list",JsonObject()) }
    LaunchedEffect(data) {
        if(data==null)return@LaunchedEffect
        data["commands"]?.let { commands=it.asJsonArray }
        data["message"]?.let { message=it.asString }
        if(data["ok"]?.asBoolean==false){polling=false;return@LaunchedEffect}
        when(data["operation"]?.asString) {
            "learn" -> {captureId=data["capture_id"]?.asString.orEmpty();captured=false;polling=true;message="Rikta fjärren mot mottagaren och tryck en gång. Du har 60 sekunder."}
            "capture" -> when(data["status"]?.asString) {
                "captured" -> {captured=true;polling=false;val count=data["signal"]?.asJsonObject?.get("durations_us")?.asJsonArray?.size();val protocol=data["metadata"]?.asJsonObject?.get("protocol")?.asString ?: "raw";message="Signal fångad: $protocol, $count pulstider. Kontrollera namn och röstfras före sparning."}
                "expired" -> {polling=false;captured=false;message="Tiden gick ut. Starta en ny inspelning."}
            }
            "save" -> {captured=false;captureId="";message="Sparat. Prova kommandot och bekräfta apparatens svar."}
            "cancel" -> {polling=false;captured=false;captureId="";message="Inspelningen avbruten."}
            "aliases" -> message="Röstfraserna är sparade."
            "execute" -> testId=data["request_id"]?.asString.orEmpty()
            "confirm" -> {testId="";message="Din observation är sparad."}
        }
    }
    LaunchedEffect(polling,captureId) {
        if(polling && captureId.isNotBlank())repeat(40){delay(1500);if(!latestBusy)latestRequest("capture",JsonObject().apply{addProperty("capture_id",captureId)})}
    }
    Text("TV apparater",style=MaterialTheme.typography.headlineMedium)
    Text("Rösten först. Säg exempelvis ”sänk volymen på Logitech”. Varje fras utför ett sparat kommando en gång.")
    if(message.isNotBlank())Text(message)
    OutlinedButton(onClick={onRequest("list",JsonObject())},enabled=!busy){Text("Uppdatera kommandon")}
    commands.forEach { value ->
        val c=value.asJsonObject;val id=c["id"].asString
        var edit by remember(id,c["aliases"].toString()){mutableStateOf(c["aliases"].asJsonArray.joinToString("; "){it.asString})}
        Card(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Column(Modifier.padding(14.dp)) {
            Text(c["device"].asString+" · "+c["label"].asString,style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(edit,{edit=it},label={Text("Röstfraser, separera med ;")},modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={onRequest("aliases",JsonObject().apply{addProperty("command_id",id);add("aliases",phrases(edit))})},enabled=!busy && edit.isNotBlank()){Text("Spara röstfraser")}
            if(backup)Button(onClick={onRequest("execute",JsonObject().apply{addProperty("command_id",id);addProperty("request_id",UUID.randomUUID().toString())})},enabled=!busy){Text("Prova en gång")}
        } }
    }
    Row { Checkbox(backup,{backup=it});Text("Visa reservknappar och test") }
    if(testId.isNotBlank()) {
        Text("Reagerade apparaten? Sändningskvittensen bekräftar inte apparatens tillstånd.")
        Row { listOf(true,false).forEach { responded -> OutlinedButton(onClick={onRequest("confirm",JsonObject().apply{addProperty("request_id",testId);addProperty("responded",responded)})},enabled=!busy){Text(if(responded)"Ja" else "Nej")} } }
    }
    HorizontalDivider()
    Text("Spela in en fjärrknapp",style=MaterialTheme.typography.titleLarge)
    Text("För TV och ljudutrustning. En inspelning sparar en knapp; använd korta tryck. Namn och fras kan fyllas i före inspelningen.")
    OutlinedTextField(device,{device=it},label={Text("Apparat")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(label,{label=it},label={Text("Knappens namn, exempelvis HDMI 1")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(aliases,{aliases=it},label={Text("Vad vill du säga? Separera fraser med ;")},modifier=Modifier.fillMaxWidth())
    Button(onClick={onRequest("learn",JsonObject())},enabled=!busy && !polling){Text("Starta inspelning")}
    if(captureId.isNotBlank())OutlinedButton(onClick={onRequest("cancel",JsonObject().apply{addProperty("capture_id",captureId)})},enabled=!busy){Text("Avbryt inspelningen")}
    if(captureId.isNotBlank() && message.isNotBlank())Text(message)
    if(captured)Button(onClick={onRequest("save",JsonObject().apply{addProperty("capture_id",captureId);addProperty("device",device);addProperty("label",label);add("aliases",phrases(aliases))})},enabled=!busy && device.isNotBlank() && label.isNotBlank() && aliases.isNotBlank()){Text("Spara signal och röstfras")}
    HorizontalDivider()
    Text("EutherBeam",style=MaterialTheme.typography.titleLarge)
    Text("Samsung, NEC och Android TV: upptäckt, parkoppling och reservfjärr. Para om enheterna här; parkopplingar från den separata appen kan inte läsas av EutherVox.")
    Text("Röstexempel: ”sätt på Samsung”, ”sänk volymen på Android TV”, ”HDMI ett på NEC”. Nätverksstyrningen via EutherBeam kräver att mobilen är på samma lokalnät.")
    OutlinedButton(onClick={beam=!beam}){Text(if(beam)"Dölj EutherBeam" else "Öppna EutherBeam och parkoppling")}
    if(beam)Box(Modifier.fillMaxWidth().height(720.dp)){EutherBeamPanel()}
}
private fun phrases(text:String)=JsonArray().apply{text.split(';').map{it.trim()}.filter{it.isNotBlank()}.forEach{add(it)}}
