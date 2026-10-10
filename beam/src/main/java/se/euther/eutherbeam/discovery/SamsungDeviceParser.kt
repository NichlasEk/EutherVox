package se.euther.eutherbeam.discovery

import com.google.gson.JsonParser

internal object SamsungDeviceParser {
    fun parseHeaders(response: String): Map<String, String> = response
        .lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null
            else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }
        .toMap()

    fun isSamsungResponse(headers: Map<String, String>): Boolean =
        headers["server"].orEmpty().contains("Samsung", ignoreCase = true) ||
            headers["st"].orEmpty().contains("samsung", ignoreCase = true) ||
            headers["usn"].orEmpty().contains("samsung", ignoreCase = true)

    fun parseDeviceInfo(address: String, json: String): SamsungTvDevice {
        val data = JsonParser.parseString(json).asJsonObject
        fun string(name: String, fallback: String = ""): String =
            data.get(name)?.takeUnless { it.isJsonNull }?.asString ?: fallback
        val model = string("ModelName", string("Model", "Samsung TV"))
        val deviceId = string("DeviceID", string("DUID"))
        val macAddress = listOf("MacAddress", "MAC", "EthernetMac", "WifiMac")
            .firstNotNullOfOrNull { name -> string(name).takeIf { it.isNotBlank() }?.let(WakeOnLan::normalizeMac) }
            ?: WakeOnLan.fromSamsungIdentifier(deviceId)
            ?: WakeOnLan.fromSamsungIdentifier(string("DUID"))
        return SamsungTvDevice(
            address = address,
            friendlyName = string("DeviceName", "Samsung TV"),
            modelName = model,
            deviceId = deviceId,
            serviceUri = string("ServiceURI", "http://$address:8001/ms/1.0/"),
            encrypted = string("Model").startsWith("14_") || model.contains("H6", true),
            networkType = string("NetworkType", "unknown"),
            macAddress = macAddress,
        )
    }
}
