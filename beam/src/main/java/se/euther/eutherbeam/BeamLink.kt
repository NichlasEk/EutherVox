package se.euther.eutherbeam

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.euther.eutherbeam.androidtv.AndroidTvDevice

/** Local, same-signature bridge. Pairing keys remain in the Beam app's keystore. */
internal class BeamLink(private val context: Context) {
    fun available(): Boolean = context.packageName != PACKAGE &&
        context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName == PACKAGE &&
        context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH

    suspend fun call(method: String, target: String = "", command: String = "", pcm: ByteArray? = null): Bundle = withContext(Dispatchers.IO) {
        val args = Bundle().apply { putString("target", target); putString("command", command); pcm?.let { putByteArray("pcm", it) } }
        val result = context.contentResolver.call(android.net.Uri.parse("content://$AUTHORITY"), method, null, args)
            ?: error("EutherBeam lämnade inget svar")
        check(result.getBoolean("ok")) { result.getString("message") ?: "EutherBeam-kommandot misslyckades" }
        result
    }

    suspend fun players(): List<AndroidTvDevice> = Gson().fromJson(
        call("players").getString("players"), object : TypeToken<List<AndroidTvDevice>>() {}.type)

    companion object {
        const val PACKAGE = "se.euther.eutherbeam"
        const val AUTHORITY = "se.euther.eutherbeam.control"
        const val PERMISSION = "se.euther.eutherbeam.permission.CONTROL"
    }
}
