package se.euther.euthervox.protocol

import org.junit.Assert.*
import org.junit.Test

class AppearanceProtocolTest {
    @Test fun oldGatewayDoesNotOverwriteCachedTheme() {
        val event = parseServerEvent("""{"type":"session.ready","session_id":"s"}""") as ServerEvent.Ready
        assertNull(event.appearanceTheme)
    }
    @Test fun themeAndSaveAcknowledgementAreDecoded() {
        val ready = parseServerEvent("""{"type":"session.ready","session_id":"s","appearance_theme":"system_regis"}""") as ServerEvent.Ready
        assertEquals("system_regis", ready.appearanceTheme)
        val saved = parseServerEvent("""{"type":"appearance.saved","theme":"classic"}""") as ServerEvent.AppearanceSaved
        assertEquals("classic", saved.theme)
    }
}
