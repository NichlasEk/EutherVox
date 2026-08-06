package se.euther.euthervox.network

import org.junit.Assert.assertEquals
import org.junit.Test

class EutherAuthTest {
    @Test fun derivesHttpsLoginOriginFromPublicWebSocket() {
        assertEquals(
            "https://apothictech.se",
            EutherAuthClient.httpOrigin("wss://apothictech.se/euthervox/ws"),
        )
    }

    @Test fun preservesExplicitLanPort() {
        assertEquals(
            "http://192.168.32.88:8788",
            EutherAuthClient.httpOrigin("ws://192.168.32.88:8788"),
        )
    }

    @Test fun derivesYouTubeOAuthUrlFromPublicWebSocket() {
        assertEquals(
            "https://apothictech.se/euthervox/oauth/start",
            EutherAuthClient.youtubeOAuthUrl("wss://apothictech.se/euthervox/ws"),
        )
    }
}
