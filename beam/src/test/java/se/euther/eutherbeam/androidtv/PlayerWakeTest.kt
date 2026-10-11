package se.euther.eutherbeam.androidtv

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PlayerWakeTest {
    @Test fun pairedRemoteWorksWithoutCast() = runBlocking {
        val calls = mutableListOf<String>()
        val result = wakePlayer(true, { calls += "wake" }, { calls += "cast" })
        assertEquals(listOf("wake"), calls)
        assertTrue(result.contains("Android-fjärrkontrollen"))
    }
    @Test fun castFallbackWhenRemoteSleeps() = runBlocking {
        val calls = mutableListOf<String>()
        wakePlayer(true, { calls += "wake"; error("offline") }, { calls += "cast" })
        assertEquals(listOf("wake", "cast"), calls)
    }
    @Test fun unpairedSkipsRemoteAndReportsOffline() = runBlocking {
        var called = false
        try {
            wakePlayer(false, { called = true }, { error("offline") })
            fail("must report failure")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message!!.contains("Pucken svarar inte"))
        }
        assertFalse(called)
    }
    @Test fun cancellationNeverTriggersCast() = runBlocking {
        var called = false
        try { wakePlayer(true, { throw CancellationException() }, { called = true }) }
        catch (_: CancellationException) { }
        assertFalse(called)
    }
}
