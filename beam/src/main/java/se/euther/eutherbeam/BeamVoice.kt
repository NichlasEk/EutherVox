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
    private val link = BeamLink(context)
    private fun linkedPlayer(target: String): AndroidTvDevice {
        require(target in setOf("android_tv_nec", "android_tv_samsung")) { "Välj TV-rum Uppe eller Nere" }
        val candidates = AndroidTvDeviceStore(context).load().filter { it.linkedDisplay == target.removePrefix("android_tv_") }
        check(candidates.size == 1) { "Koppla exakt en Android TV-puck till TV-kortet först" }
        return candidates.single()
    }

    suspend fun search(target: String, title: String, pcm: ByteArray): String = lock.withLock {
        if (link.available()) return@withLock link.call("search", target, title, pcm).getString("message").orEmpty()
        require(title.length in 1..160)
        val device = linkedPlayer(target)
        check(device.paired) { "Para TV-kortets Androidpuck först" }
        AndroidTvRemoteClient(AndroidTvIdentity(), AndroidTvProtocol.MEDIA_FEATURES).use { it.search(device.address, pcm) }
        "Sökningen efter $title är skickad till ${device.name}. Kontrollera sökresultatet på TV:n."
    }

    suspend fun status(target: String): String = lock.withLock {
        if (link.available()) return@withLock link.call("status", target).getString("message").orEmpty()
        val device = linkedPlayer(target)
        val cast = runCatching { CastPlaybackClient(AndroidTvIdentity().sslContext()).read(device.address, device.castPort) }.getOrNull()
        if (cast?.title != null) return@withLock cast.describe()
        val app = if (device.paired) runCatching {
            AndroidTvRemoteClient(AndroidTvIdentity(), AndroidTvProtocol.MEDIA_FEATURES).use { it.readCurrentApp(device.address) }
        }.getOrNull() else null
        val name = mapOf("com.netflix.ninja" to "Netflix", "com.google.android.youtube.tv" to "YouTube", "se.svt.android.svtplay" to "SVT Play", "com.disney.disneyplus" to "Disney+")[app] ?: app
        if (name != null) "Aktiv app på ${device.name}: $name. Filmtiteln är inte tillgänglig."
        else cast?.describe() ?: "Jag kunde inte läsa aktuell uppspelning från ${device.name}."
    }

    suspend fun execute(target: String, command: String): String = lock.withLock {
        if (link.available()) return@withLock link.call("control", target, command).getString("message").orEmpty()
        val samsung = mapOf("volume_up" to "KEY_VOLUP", "volume_down" to "KEY_VOLDOWN", "mute" to "KEY_MUTE", "power_off" to "KEY_POWEROFF", "hdmi1" to "KEY_HDMI1", "hdmi2" to "KEY_HDMI2", "home" to "KEY_MENU", "up" to "KEY_UP", "down" to "KEY_DOWN", "left" to "KEY_LEFT", "right" to "KEY_RIGHT", "ok" to "KEY_ENTER", "back" to "KEY_RETURN", "play" to "KEY_PLAY", "pause" to "KEY_PAUSE")
        val android = mapOf("volume_up" to AndroidTvKey.VOLUME_UP, "volume_down" to AndroidTvKey.VOLUME_DOWN, "mute" to AndroidTvKey.VOLUME_MUTE, "power_on" to AndroidTvKey.WAKEUP, "power_off" to AndroidTvKey.SLEEP, "home" to AndroidTvKey.HOME, "up" to AndroidTvKey.DPAD_UP, "down" to AndroidTvKey.DPAD_DOWN, "left" to AndroidTvKey.DPAD_LEFT, "right" to AndroidTvKey.DPAD_RIGHT, "ok" to AndroidTvKey.DPAD_CENTER, "back" to AndroidTvKey.BACK, "play" to AndroidTvKey.MEDIA_PLAY, "pause" to AndroidTvKey.MEDIA_PAUSE)
        when(target) {
            "samsung" -> {
                val device=SamsungDeviceStore(context).load() ?: error("Spara Samsung-TV:n under EutherBeam först")
                if(command=="power_on") {
                    val puck = linkedPlayer("android_tv_samsung")
                    CastCecWakeClient(AndroidTvIdentity().sslContext()).wake(puck.address,puck.castPort)
                } else {
                    val key=samsung[command] ?: error("Kommandot stöds inte för Samsung")
                    val identity=SamsungIdentityStore(context).load(device.deviceId) ?: error("Para Samsung-TV:n i EutherVox först")
                    SamsungRemoteSession(device.address,identity,device.deviceId).sendKey(key)
                }
            }
            "android_tv", "android_tv_nec", "android_tv_samsung" -> {
                val store=AndroidTvDeviceStore(context)
                val device = if (target == "android_tv") {
                    store.load().firstOrNull { it.id == store.selectedId() }
                        ?: error("Välj en sparad Android TV-puck först")
                } else {
                    val display = target.removePrefix("android_tv_")
                    val candidates = store.load().filter { it.linkedDisplay == display }
                    check(candidates.size == 1) { "Koppla exakt en Android TV-puck till rummets TV i EutherBeam" }
                    candidates.single()
                }
                val key=android[command] ?: error("Kommandot stöds inte för Android TV")
                if(command=="power_on") return@withLock wakePlayer(device)
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
