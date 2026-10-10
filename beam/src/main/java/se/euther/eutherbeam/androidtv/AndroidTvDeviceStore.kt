package se.euther.eutherbeam.androidtv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal data class AndroidTvDevice(
    val id: String,
    val name: String,
    val address: String,
    val model: String = "Android TV",
    val supportsRemote: Boolean = false,
    val supportsCast: Boolean = false,
    val castPort: Int = 8009,
    val paired: Boolean = false,
    val linkedDisplay: String = "nec",
)

internal class AndroidTvDeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences("eutherbeam", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<AndroidTvDevice>>() {}.type

    fun load(): List<AndroidTvDevice> {
        val stored = preferences.getString(KEY_DEVICES, null)?.let { json ->
            runCatching { gson.fromJson<List<AndroidTvDevice>>(json, listType) }.getOrNull()
        }.orEmpty()
        if (stored.isNotEmpty()) return stored

        val legacyAddress = preferences.getString("android_tv_ip", "").orEmpty()
        if (legacyAddress.isBlank()) return emptyList()
        val legacy = AndroidTvDevice(
            id = "ip:$legacyAddress",
            name = preferences.getString("android_tv_name", "Android TV").orEmpty().ifBlank { "Android TV" },
            address = legacyAddress,
            supportsRemote = true,
            paired = preferences.getString("android_tv_paired_host", "") == legacyAddress,
            linkedDisplay = preferences.getString("android_tv_linked_display", "nec").orEmpty(),
        )
        save(listOf(legacy), legacy.id)
        return listOf(legacy)
    }

    fun selectedId(): String? = preferences.getString(KEY_SELECTED, null)

    fun save(devices: List<AndroidTvDevice>, selectedId: String? = selectedId()) {
        preferences.edit()
            .putString(KEY_DEVICES, gson.toJson(devices))
            .apply { selectedId?.let { putString(KEY_SELECTED, it) } }
            .apply()
    }

    fun merge(saved: List<AndroidTvDevice>, discovered: List<AndroidTvDevice>): List<AndroidTvDevice> {
        val result = saved.toMutableList()
        discovered.forEach { fresh ->
            val index = result.indexOfFirst { old ->
                old.id == fresh.id || old.address == fresh.address
            }
            if (index < 0) {
                result += fresh
            } else {
                val old = result[index]
                result[index] = fresh.copy(
                    id = if (fresh.id.startsWith("ip:")) old.id else fresh.id,
                    paired = old.paired,
                    linkedDisplay = old.linkedDisplay,
                )
            }
        }
        return result.sortedWith(compareByDescending<AndroidTvDevice> { it.paired }.thenBy { it.name.lowercase() })
    }

    private companion object {
        const val KEY_DEVICES = "android_tv_devices_v1"
        const val KEY_SELECTED = "android_tv_selected_device_v1"
    }
}
