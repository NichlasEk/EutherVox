package se.euther.eutherbeam.discovery

data class SamsungTvDevice(
    val address: String,
    val friendlyName: String,
    val modelName: String,
    val deviceId: String,
    val serviceUri: String,
    val encrypted: Boolean,
    val networkType: String,
    val macAddress: String? = null,
)
