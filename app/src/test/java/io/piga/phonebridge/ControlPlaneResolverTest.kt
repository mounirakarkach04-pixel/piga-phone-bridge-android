package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ControlPlaneResolverTest {
    @Test
    fun acceptsOnlyCanonicalOrigin() {
        assertEquals(
            "https://app.pigapocket.com",
            ControlPlaneResolver.validateEndpoint("https://app.pigapocket.com/"),
        )
    }

    @Test
    fun rejectsUntrustedHostsAndUrlComponents() {
        listOf(
            "http://app.pigapocket.com",
            "https://pigapocket.com",
            "https://www.pigapocket.com",
            "https://api.pigapocket.com",
            "https://app.pigapocket.com:8443",
            "https://user:pass@app.pigapocket.com",
            "https://app.pigapocket.com/api",
            "https://app.pigapocket.com?x=1",
            "https://app.pigapocket.com#fragment",
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlPlaneResolver.validateEndpoint(candidate)
            }
        }
    }
}
