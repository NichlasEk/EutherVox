package se.euther.eutherbeam.discovery

import android.content.Context
import com.google.gson.Gson

internal class SamsungDeviceStore(context: Context, private val gson: Gson = Gson()) {
    private val preferences = context.getSharedPreferences("eutherbeam", Context.MODE_PRIVATE)

    fun load(): SamsungTvDevice? = runCatching {
        preferences.getString(KEY, null)?.let { gson.fromJson(it, SamsungTvDevice::class.java) }
    }.getOrNull()

    fun save(device: SamsungTvDevice) {
        preferences.edit().putString(KEY, gson.toJson(device)).apply()
    }

    private companion object {
        const val KEY = "saved_samsung_device_v1"
    }
}
