package com.mggx.pccontrol.next.home

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HttpAgentGatewayTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun authenticatedAgentCommandsKeepBearerOnOriginalHostAndDoNotRedirect() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val reply = HttpAgentGateway(server.url("/").toString(), "AGENT_TEST_TOKEN").command("api/v1/power/lock")
        assertEquals(202, reply.code)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer AGENT_TEST_TOKEN", request.getHeader("Authorization"))
        assertEquals("/api/v1/power/lock", request.path)
    }
}
