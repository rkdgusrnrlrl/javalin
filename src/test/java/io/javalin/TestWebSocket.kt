/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin

import com.mashape.unirest.http.Unirest
import io.javalin.ApiBuilder.ws
import io.javalin.util.TestUtil
import io.javalin.websocket.WsSession
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.not
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * This test could be better
 */
class TestWebSocket {

    private val contextPathJavalin = Javalin.create().contextPath("/websocket")
    private var log = mutableListOf<String>()

    @Before
    fun resetLog() {
        log = ArrayList()
    }

    @Test
    fun `each connection receives a unique id`() = TestUtil.test(contextPathJavalin) { app, _ ->
        app.ws("/test-websocket-1") { ws ->
            ws.onConnect { session -> log.add(session.id) }
            ws.onMessage { session, _ -> log.add(session.id) }
            ws.onClose { session, _, _ -> log.add(session.id) }
        }
        app.routes {
            ws("/test-websocket-2") { ws ->
                ws.onConnect { session -> log.add(session.id) }
                ws.onMessage { session, _ -> log.add(session.id) }
                ws.onClose { session, _, _ -> log.add(session.id) }
            }
        }

        val testClient1_1 = TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/test-websocket-1"))
        val testClient1_2 = TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/test-websocket-1"))
        val testClient2_1 = TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/test-websocket-2"))

        doAndSleepWhile({ testClient1_1.connect() }, { !testClient1_1.isOpen })
        doAndSleepWhile({ testClient1_2.connect() }, { !testClient1_2.isOpen })
        doAndSleep { testClient1_1.send("A") }
        doAndSleep { testClient1_2.send("B") }
        doAndSleepWhile({ testClient1_1.close() }, { testClient1_1.isClosing })
        doAndSleepWhile({ testClient1_2.close() }, { testClient1_2.isClosing })
        doAndSleepWhile({ testClient2_1.connect() }, { !testClient2_1.isOpen })
        doAndSleepWhile({ testClient2_1.close() }, { testClient2_1.isClosing })

        // 3 clients and a lot of operations should only yield three unique identifiers for the clients
        val uniqueLog = HashSet(log)
        assertThat(uniqueLog, hasSize(3))
        for (id in uniqueLog) {
            assertThat(uniqueLog.count { it == id }, `is`(1))
        }
    }

    @Test
    fun `general integration test`() = TestUtil.test(contextPathJavalin) { app, _ ->
        val userUsernameMap = LinkedHashMap<WsSession, Int>()
        val atomicInteger = AtomicInteger()
        app.ws("/test-websocket-1") { ws ->
            ws.onConnect { session ->
                userUsernameMap[session] = atomicInteger.getAndIncrement()
                log.add(userUsernameMap[session].toString() + " connected")
            }
            ws.onMessage { session, message ->
                log.add(userUsernameMap[session].toString() + " sent '" + message + "' to server")
                userUsernameMap.forEach { client, _ -> doAndSleep { client.send("Server sent '" + message + "' to " + userUsernameMap[client]) } }
            }
            ws.onClose { session, _, _ -> log.add(userUsernameMap[session].toString() + " disconnected") }
        }
        app.routes {
            ws("/test-websocket-2") { ws ->
                ws.onConnect { _ -> log.add("Connected to other endpoint") }
                ws.onClose { _, _, _ -> log.add("Disconnected from other endpoint") }
            }
        }

        val testClient1_1 = TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/test-websocket-1"))
        val testClient1_2 = TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/test-websocket-1"))
        val testClient2_1 = TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/test-websocket-2"))

        doAndSleepWhile({ testClient1_1.connect() }, { !testClient1_1.isOpen })
        doAndSleepWhile({ testClient1_2.connect() }, { !testClient1_2.isOpen })
        doAndSleep { testClient1_1.send("A") }
        doAndSleep { testClient1_2.send("B") }
        doAndSleepWhile({ testClient1_1.close() }, { testClient1_1.isClosing })
        doAndSleepWhile({ testClient1_2.close() }, { testClient1_2.isClosing })
        doAndSleepWhile({ testClient2_1.connect() }, { !testClient2_1.isOpen })
        doAndSleepWhile({ testClient2_1.close() }, { testClient2_1.isClosing })
        assertThat(log, containsInAnyOrder(
                "0 connected",
                "1 connected",
                "0 sent 'A' to server",
                "Server sent 'A' to 0",
                "Server sent 'A' to 1",
                "1 sent 'B' to server",
                "Server sent 'B' to 0",
                "Server sent 'B' to 1",
                "0 disconnected",
                "1 disconnected",
                "Connected to other endpoint",
                "Disconnected from other endpoint"
        ))
    }

    @Test
    fun `routing and pathParams() work`() = TestUtil.test(contextPathJavalin) { app, _ ->
        app.ws("/params/:1") { ws -> ws.onConnect { session -> log.add(session.pathParam("1")) } }
        app.ws("/params/:1/test/:2/:3") { ws -> ws.onConnect { session -> log.add(session.pathParam("1") + " " + session.pathParam("2") + " " + session.pathParam("3")) } }
        app.ws("/*") { ws -> ws.onConnect { session -> log.add("catchall") } } // this should not be triggered since all calls match more specific handlers
        TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/params/one")).let {
            doAndSleepWhile({ it.connect() }, { !it.isOpen })
            doAndSleepWhile({ it.close() }, { it.isClosing })
        }
        TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/params/%E2%99%94")).let {
            doAndSleepWhile({ it.connect() }, { !it.isOpen })
            doAndSleepWhile({ it.close() }, { it.isClosing })
        }
        TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/params/another/test/long/path")).let {
            doAndSleepWhile({ it.connect() }, { !it.isOpen })
            doAndSleepWhile({ it.close() }, { it.isClosing })
        }
        assertThat(log, containsInAnyOrder("one", "♔", "another long path"))
        assertThat(log, not(hasItem("catchall")))
    }

    @Test
    fun `websocket 404 works`() = TestUtil.test { app, _ ->
        val response = Unirest.get("http://localhost:" + app.port() + "/invalid-path")
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Host", "localhost:" + app.port())
                .header("Sec-WebSocket-Key", "SGVsbG8sIHdvcmxkIQ==")
                .header("Sec-WebSocket-Version", "13")
                .asString()
        assertThat(response.body, containsString("WebSocket handler not found"))
    }

    @Test
    fun `headers and host are available in session`() = TestUtil.test { app, _ ->
        app.ws("websocket") { ws ->
            ws.onConnect { session -> log.add("Header: " + session.header("Test")!!) }
            ws.onClose { session, _, _ -> log.add("Closed connection from: " + session.host()!!) }
        }
        TestClient(URI.create("ws://localhost:" + app.port() + "/websocket"), mapOf("Test" to "HeaderParameter")).let {
            doAndSleepWhile({ it.connect() }, { !it.isOpen })
            doAndSleepWhile({ it.close() }, { it.isClosing })
        }
        assertThat(log, containsInAnyOrder("Header: HeaderParameter", "Closed connection from: localhost"))
    }

    @Test
    fun `extracting path information works`() = TestUtil.test { app, _ ->
        var matchedPath = ""
        var pathParam = ""
        var queryParam = ""
        var queryParams = listOf<String>()
        app.ws("/websocket/:channel") { ws ->
            ws.onConnect { session ->
                matchedPath = session.matchedPath()
                pathParam = session.pathParam("channel")
                queryParam = session.queryParam("qp")!!
                queryParams = session.queryParams("qps")
            }
        }
        TestClient(URI.create("ws://localhost:" + app.port() + "/websocket/channel-one?qp=just-a-qp&qps=1&qps=2")).let {
            doAndSleepWhile({ it.connect() }, { !it.isOpen })
            doAndSleepWhile({ it.close() }, { it.isClosing })
        }
        assertThat(matchedPath, `is`("/websocket/:channel"))
        assertThat(pathParam, `is`("channel-one"))
        assertThat(queryParam, `is`("just-a-qp"))
        assertThat(queryParams, contains("1", "2"))
    }

    internal inner class TestClient : WebSocketClient {
        constructor(serverUri: URI) : super(serverUri)
        constructor(serverUri: URI, headers: Map<String, String>) : super(serverUri, Draft_6455(), headers, 0) {}

        override fun onMessage(s: String) {
            log.add(s)
        }

        override fun onOpen(serverHandshake: ServerHandshake) {}
        override fun onClose(i: Int, s: String, b: Boolean) {}
        override fun onError(e: Exception) {}
    }

    private fun doAndSleepWhile(func1: () -> Unit, func2: () -> Boolean) {
        val timeMillis = System.currentTimeMillis()
        func1.invoke()
        while (func2.invoke() && System.currentTimeMillis() < timeMillis + 1000) {
            Thread.sleep(25)
        }
    }

    private fun doAndSleep(func: () -> Unit) {
        func.invoke()
        Thread.sleep(25)
    }

}
