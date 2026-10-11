package se.euther.eutherbeam.androidtv

import kotlinx.coroutines.CancellationException

/** Prefer an explicit wake key; Cast is a fallback for sleeping remote services. */
internal suspend fun wakePlayer(
    paired: Boolean,
    remote: suspend () -> Unit,
    cast: suspend () -> Unit,
): String {
    if (paired) {
        try {
            remote()
            return "Väckningskommandot är skickat via Android-fjärrkontrollen."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // WAKEUP is idempotent, unlike the POWER toggle. Try the other transport.
        }
    }
    try {
        cast()
        return "Väckning är skickad via Cast. Kontrollera om pucken vaknar."
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw IllegalStateException(
            "Pucken svarar inte på nätverksväckning. Kontrollera ström och nätverk; " +
                "om nätverket stängs av i standby behöver den väckas med sin fjärrkontroll.", failure)
    }
}

internal suspend fun wakePlayer(device: AndroidTvDevice): String = wakePlayer(
    paired = device.paired,
    remote = { AndroidTvRemoteClient(AndroidTvIdentity()).use { it.sendKey(device.address, AndroidTvKey.WAKEUP) } },
    cast = { CastCecWakeClient(AndroidTvIdentity().sslContext()).wake(device.address, device.castPort) },
)
