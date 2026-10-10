package se.euther.eutherbeam

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.euther.eutherbeam.discovery.SamsungDeviceStore
import se.euther.eutherbeam.protocol.SamsungIdentityStore
import se.euther.eutherbeam.protocol.SamsungRemoteSession
import se.euther.eutherbeam.androidtv.*
import se.euther.eutherbeam.nec.*

/** Only selected, explicitly saved/paired devices. No arbitrary address or key. */
class BeamVoice(private val context: Context) {
    private val lock = Mutex()
    suspend fun execute(target: String, command: String): String = lock.withLock {
        val samsung = mapOf("volume_up" to "KEY_VOLUP", "volume_down" to "KEY_VOLDOWN", "mute" to "KEY_MUTE", "power_off" to "KEY_POWEROFF", "hdmi1" to "KEY_HDMI1", "home" to "KEY_MENU", "up" to "KEY_UP", "down" to "KEY_DOWN", "left" to "KEY_LEFT", "right" to "KEY_RIGHT", "ok" to "KEY_ENTER", "back" to "KEY_RETURN", "play" to "KEY_PLAY", "pause" to "KEY_PAUSE")
        val android = mapOf("volume_up" to AndroidTvKey.VOLUME_UP, "volume_down" to AndroidTvKey.VOLUME_DOWN, "mute" to AndroidTvKey.VOLUME_MUTE, "power_on" to AndroidTvKey.WAKEUP, "power_off" to AndroidTvKey.SLEEP, "home" to AndroidTvKey.HOME, "up" to AndroidTvKey.DPAD_UP, "down" to AndroidTvKey.DPAD_DOWN, "left" to AndroidTvKey.DPAD_LEFT, "right" to AndroidTvKey.DPAD_RIGHT, "ok" to AndroidTvKey.DPAD_CENTER, "back" to AndroidTvKey.BACK, "play" to AndroidTvKey.MEDIA_PLAY_PAUSE, "pause" to AndroidTvKey.MEDIA_PLAY_PAUSE)
        when(target) {
            "samsung" -> {
                val device=SamsungDeviceStore(context).load() ?: error("Spara Samsung-TV:n under EutherBeam först")
                if(command=="power_on") {
                    val puck=AndroidTvDeviceStore(context).load().firstOrNull { it.linkedDisplay=="samsung" } ?: error("Koppla en Android TV-puck till Samsung för Cast-väckning")
                    CastCecWakeClient(AndroidTvIdentity().sslContext()).wake(puck.address,puck.castPort)
                } else {
                    val key=samsung[command] ?: error("Kommandot stöds inte för Samsung")
                    val identity=SamsungIdentityStore(context).load(device.deviceId) ?: error("Para Samsung-TV:n i EutherVox först")
                    SamsungRemoteSession(device.address,identity,device.deviceId).sendKey(key)
                }
            }
            "android_tv" -> {
                val store=AndroidTvDeviceStore(context)
                val device=store.load().firstOrNull { it.id==store.selectedId() } ?: error("Välj en sparad Android TV-puck först")
                val key=android[command] ?: error("Kommandot stöds inte för Android TV")
                if(command=="power_on")CastCecWakeClient(AndroidTvIdentity().sslContext()).wake(device.address,device.castPort)
                else {
                    check(device.paired) { "Para Android TV-pucken först" }
                    AndroidTvRemoteClient(AndroidTvIdentity()).use { it.sendKey(device.address,key) }
                }
            }
            "nec" -> {
                val host=Ipv4Subnet.normalizeAddress(context.getSharedPreferences("eutherbeam",Context.MODE_PRIVATE).getString("nec_display_ip", "").orEmpty()) ?: error("Spara NEC-TV:n först")
                val client=NecRemoteClient()
                when(command) {
                    "power_on" -> client.sendPower(host,true)
                    "power_off" -> client.sendPower(host,false)
                    "hdmi1" -> client.sendInput(host,"0011")
                    "hdmi2" -> client.sendInput(host,"0012")
                    else -> error("Kommandot stöds inte för NEC")
                }
            }
            else -> error("Okänd TV-typ")
        }
        "Kommandot är skickat via EutherBeam. Kontrollera TV:ns svar."
    }
}
