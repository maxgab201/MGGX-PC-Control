package com.mggx.pccontrol.next.data

import com.mggx.pccontrol.next.domain.PcState
import com.mggx.pccontrol.next.domain.RelayConfig
import com.mggx.pccontrol.next.domain.RelayError
import com.mggx.pccontrol.next.domain.RelayResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException

class HttpRelayApiIntegrationTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun api(token: String = "TEST_TOKEN", timeout: Int = 2) = HttpRelayApi(RelayConfig(server.url("/").toString(), token, "main", timeout))
    private fun status(state: String = "offline") = """{"ok":true,"apiVersion":1,"pcId":"main","state":"$state","status":"$state","lastSeen":null,"latencyMs":0,"latency":0,"monitors":[]}"""

    @Test fun normalizesPrivateUrlsAndKnownPaths() {
        val one = RelayUrlNormalizer.normalize("100.77.114.61:8765") as RelayResult.Success
        assertEquals("http://100.77.114.61:8765", one.value.displayUrl)
        val two = RelayUrlNormalizer.normalize("http://100.77.114.61:8765/api/v1/") as RelayResult.Success
        assertEquals("http://100.77.114.61:8765", two.value.displayUrl)
        assertTrue(RelayUrlNormalizer.normalize("http://100.77.114.61:99999") is RelayResult.Failure)
        assertTrue(RelayUrlNormalizer.normalize("ftp://100.77.114.61:8765") is RelayResult.Failure)
        assertTrue(RelayUrlNormalizer.normalize("http://example.com:8765") is RelayResult.Failure)
        assertEquals("http://relay.tailnet.ts.net:8765", (RelayUrlNormalizer.normalize("relay.tailnet.ts.net:8765") as RelayResult.Success).value.displayUrl)
        assertEquals("http://[fd7a:115c:a1e0::1]:8765", (RelayUrlNormalizer.normalize("[fd7a:115c:a1e0::1]:8765/health") as RelayResult.Success).value.displayUrl)
        assertTrue(RelayUrlNormalizer.normalize("http://100.77.114.61:0") is RelayResult.Failure)
        assertTrue(RelayUrlNormalizer.normalize("http://100.77.114.61:8765/api/v2") is RelayResult.Failure)
    }

    @Test fun healthAndAuthorizedOfflineStatusTraverseRealHttp() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"service":"mggx-relay","version":2}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(status()))
        val health = api("  TEST_TOKEN\n").getHealth()
        assertTrue(health is RelayResult.Success); assertEquals(2, (health as RelayResult.Success).value.version)
        assertNull(server.takeRequest().getHeader("Authorization"))
        val result = api("  TEST_TOKEN\n").getStatus()
        assertTrue(result is RelayResult.Success); assertEquals(PcState.OFFLINE, (result as RelayResult.Success).value.state)
        val request = server.takeRequest(); assertEquals("Bearer TEST_TOKEN", request.getHeader("Authorization")); assertEquals("/api/v1/status", request.path)
    }

    @Test fun distinguishesHttpFailures() = runTest {
        listOf(401 to RelayError.Unauthorized, 403 to RelayError.Forbidden, 404 to RelayError.ApiVersionMismatch).forEach { (code, expected) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}")); val failure = api().getStatus() as RelayResult.Failure; assertEquals(expected, failure.error)
        }
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"pc_not_configured"}""")); assertTrue((api().getStatus() as RelayResult.Failure).error is RelayError.Functional)
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}")); assertTrue((api().getStatus() as RelayResult.Failure).error is RelayError.Server)
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}")); assertEquals(RelayError.RateLimited, (api().getStatus() as RelayResult.Failure).error)
    }

    @Test fun malformedJsonAndWake202AreHandled() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json")); assertEquals(RelayError.InvalidResponse, (api().getStatus() as RelayResult.Failure).error); assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"state":"waking"}""")); val wake = api().wake(); assertTrue(wake is RelayResult.Success); assertEquals(202, (wake as RelayResult.Success).httpCode); assertEquals("POST", server.takeRequest().method)
    }

    @Test fun timeoutAndConnectionRefusedAreNotCollapsed() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); assertEquals(RelayError.Timeout, (api(timeout = 1).getStatus() as RelayResult.Failure).error)
        assertEquals(RelayError.ConnectionRefused, classifyThrowable(ConnectException("Connection refused")))
    }

    @Test fun parsesNewStatusAndAllAcceptedCommands() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"pcId":"main","state":"online","agent":{"reachable":true,"version":"1.0.0","uptimeSeconds":12},"sunshine":{"installed":true,"running":true},"tailscale":{"installed":true,"running":true,"ip":"100.64.2.3"},"capabilities":{"wake":true,"shutdown":true,"restart":true,"sleep":true,"hibernate":true,"lock":true,"sunshineRestart":true}}"""))
        val pc = (api().getStatus() as RelayResult.Success).value
        assertTrue(pc.agent.reachable == true); assertTrue(pc.sunshine.running == true); assertEquals("100.64.2.3", pc.tailscale.ip)
        val calls = listOf<suspend () -> RelayResult<Unit>>({ api().wake() }, { api().shutdown() }, { api().restart() }, { api().sleep() }, { api().hibernate() }, { api().lock() }, { api().restartSunshine() })
        calls.forEach { server.enqueue(MockResponse().setResponseCode(202).setBody("{}")); assertEquals(202, (it() as RelayResult.Success).httpCode) }
        repeat(calls.size + 1) { assertEquals("Bearer TEST_TOKEN", server.takeRequest().getHeader("Authorization")) }
    }

    @Test fun healthRejectsWrongRelayAndStatusRejectsCorruptJson() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"service":"other","version":1}"""))
        assertEquals(RelayError.InvalidResponse, (api().getHealth() as RelayResult.Failure).error)
        server.enqueue(MockResponse().setBody("{"))
        assertEquals(RelayError.InvalidResponse, (api().getStatus() as RelayResult.Failure).error)
    }
}
