package se.euther.euthervox.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser

const val PROTOCOL_VERSION = 1

fun sessionStart(nodeName: String = "android-phone", character: String = "skinnskattaren"): String =
    JsonObject().apply {
        addProperty("type", "session.start")
        addProperty("protocol_version", PROTOCOL_VERSION)
        addProperty("client_id", "android-phone")
        addProperty("node_name", nodeName)
        addProperty("room", "mobile")
        addProperty("character", character)
        add("input_audio", JsonObject().apply {
            addProperty("codec", "pcm_s16le")
            addProperty("sample_rate", 16_000)
            addProperty("channels", 1)
            addProperty("frame_ms", 20)
        })
    }.toString()

fun audioStart(utteranceId: String) = message("audio.start", utteranceId)
fun audioEnd(utteranceId: String) = message("audio.end", utteranceId)
fun responseCancel(utteranceId: String) = message("response.cancel", utteranceId)

private fun message(type: String, utteranceId: String) = JsonObject().apply {
    addProperty("type", type)
    addProperty("utterance_id", utteranceId)
}.toString()

sealed interface ServerEvent {
    data class Ready(val sessionId: String) : ServerEvent
    data class SttPartial(val utteranceId: String, val text: String) : ServerEvent
    data class SttFinal(val utteranceId: String, val text: String) : ServerEvent
    data class TextDelta(val utteranceId: String, val text: String) : ServerEvent
    data class TextFinal(val utteranceId: String, val text: String) : ServerEvent
    data class TtsStart(val utteranceId: String, val sampleRate: Int, val channels: Int) : ServerEvent
    data class TtsEnd(val utteranceId: String) : ServerEvent
    data class Cancelled(val utteranceId: String) : ServerEvent
    data class Error(val code: String, val message: String, val recoverable: Boolean) : ServerEvent
    data class Unknown(val type: String) : ServerEvent
}

fun parseServerEvent(raw: String): ServerEvent {
    val json = JsonParser.parseString(raw).asJsonObject
    val type = json["type"].asString
    val utterance = json["utterance_id"]?.asString.orEmpty()
    return when (type) {
        "session.ready" -> ServerEvent.Ready(json["session_id"].asString)
        "stt.partial" -> ServerEvent.SttPartial(utterance, json["text"].asString)
        "stt.final" -> ServerEvent.SttFinal(utterance, json["text"].asString)
        "assistant.text.delta" -> ServerEvent.TextDelta(utterance, json["text"].asString)
        "assistant.text.final" -> ServerEvent.TextFinal(utterance, json["text"].asString)
        "tts.start" -> json["audio"].asJsonObject.let {
            ServerEvent.TtsStart(utterance, it["sample_rate"].asInt, it["channels"].asInt)
        }
        "tts.end" -> ServerEvent.TtsEnd(utterance)
        "response.cancelled" -> ServerEvent.Cancelled(utterance)
        "error" -> ServerEvent.Error(json["code"].asString, json["message"].asString, json["recoverable"]?.asBoolean ?: false)
        else -> ServerEvent.Unknown(type)
    }
}
