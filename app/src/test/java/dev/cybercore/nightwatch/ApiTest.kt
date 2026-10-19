package dev.cybercore.nightwatch

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NightwatchApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // MockWebServer is plain HTTP on localhost, which normalizeBaseUrl only
        // allows for ts.net names.
        api = NightwatchApi.forTesting(server.url("/").toString(), "nw_ab_cd")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sessionSendsTheTokenAndParses() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"host":"blackbox","version":"0.1.0","device":{"id":"ab","name":"SGS25U","operate":true},
            "services":{"daemonhall":true,"dockspace":false,"cyberdeck":true,"mission":"prj_1","push":true}}"""))
        val s = api.session()
        assertEquals("blackbox", s.host)
        assertTrue(s.operate)
        assertFalse(s.dockspace)
        assertEquals("prj_1", s.missionProject)
        val req = server.takeRequest()
        assertEquals("/v1/session", req.path)
        assertEquals("Bearer nw_ab_cd", req.getHeader("Authorization"))
    }

    @Test
    fun unitIdsAreOnePathSegment() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"restart x (via nightwatch:p)"}"""))
        assertEquals("restart x (via nightwatch:p)", api.unitAction("user:a/../b@c.service", "restart"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/units/user:a%2F..%2Fb@c.service/action/restart", req.path)
    }

    @Test
    fun gatewayErrorsBecomeApiExceptions() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"ok":false,"message":"this device token is read-only"}"""))
        try {
            api.unitAction("x.service", "stop")
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
            assertEquals("this device token is read-only", e.message)
        }
    }

    @Test
    fun unitsAndStacksParse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"generated":1,"units":[{"id":"user:holodrive.service","active_state":"active",
            "status_label":"running","scope":"user","memory":1048576,"n_restarts":2,"next_run":null,"nw_actions":["start","stop","restart"],"nw_protected":false}]}"""))
        val u = api.units().single()
        assertEquals("holodrive.service", u.shortName)
        assertEquals(listOf("start", "stop", "restart"), u.actions)
        assertNull(u.nextRun)
        server.enqueue(MockResponse().setBody("""{"stacks":[{"id":"services--vault","name":"services/vault","status":"Partial",
            "services":["vault"],"ports":["8200:8200"],"containers":[{"service":"vault","state":"exited","name":"vault-vault-1"}],"last_error":null,"nw_actions":["up","restart"]}]}"""))
        val s = api.stacks().single()
        assertEquals("vault-vault-1", s.containers.single().name)
        assertNull(s.lastError)
    }

    @Test
    fun alertsAckSendsTheId() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"alerts":[{"id":7,"ts":1,"source":"argus","severity":"crit","title":"Argus: 1 integrity change","body":"New: /etc/x","subject":"/etc/x","count":1,"acked":false}],"open":1,"crit":1}"""))
        val page = api.alerts(after = 5)
        assertEquals(7L, page.alerts.single().id)
        assertEquals("/v1/alerts?limit=100&after=5", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"ok":true,"acked":1}"""))
        assertEquals(1, api.ack(7))
        assertEquals("""{"id":7}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun logsStreamUntilTheServerCloses() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"ts\":1,\"prio\":6,\"ident\":\"systemd\",\"msg\":\"Started\"}\n\n: keep-alive\n\ndata: {\"ts\":2,\"prio\":3,\"ident\":\"x\",\"msg\":\"boom\"}\n\n"))
        val lines = api.unitLogs("x.service").toList()
        assertEquals(listOf("Started", "boom"), lines.map { it.msg })
        assertEquals(3, lines[1].prio)
    }

    @Test
    fun reportPathsKeepTheirFolders() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"path":"cpu/cpu.md","markdown":"# CPU"}"""))
        assertEquals("# CPU", api.report("cpu/cpu.md"))
        assertEquals("/v1/deck/reports/cpu/cpu.md", server.takeRequest().path)
    }
}

