package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ControlPlaneResolverTest {
    @Test
    fun acceptsOnlyCanonicalOrigin() {
        assertEquals(
            "https://pigapocket.com",
            ControlPlaneResolver.validateEndpoint("https://pigapocket.com/"),
        )
    }

    @Test
    fun rejectsUntrustedHostsAndUrlComponents() {
        listOf(
            "http://pigapocket.com",
            "https://www.pigapocket.com",
            "https://api.pigapocket.com",
            "https://pigapocket.com:8443",
            "https://user:pass@pigapocket.com",
            "https://pigapocket.com/api",
            "https://pigapocket.com?x=1",
            "https://pigapocket.com#fragment",
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlPlaneResolver.validateEndpoint(candidate)
            }
        }
    }
}
