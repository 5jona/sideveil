package pochita.sideveil.daemon

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pochita.sideveil.shared.AppearancePatchParams
import pochita.sideveil.shared.DaemonStatus
import pochita.sideveil.shared.HostHelloPayload
import pochita.sideveil.shared.PanelState
import pochita.sideveil.shared.PingResult
import pochita.sideveil.shared.RpcRequest
import pochita.sideveil.shared.RpcResponse
import pochita.sideveil.shared.SideveilConfig
import pochita.sideveil.shared.SideveilMethods
import pochita.sideveil.shared.SideveilPrettyJson
import pochita.sideveil.shared.SideveilWireJson
import pochita.sideveil.shared.WebSocketCodec
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

class ControlServerAndConfigStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createTestRpcDelegate(): DaemonRpcDelegate =
        object : DaemonRpcDelegate {
            override fun buildStatus(): DaemonStatus =
                DaemonStatus(
                    pid = 4242,
                    uid = 1000,
                    uptimeMs = 12345L,
                    configVersion = 1,
                    enabled = true,
                    scopeActive = true,
                    foregroundPackage = "com.test.pkg",
                    hostPackage = "pochita.sideveil.host",
                    hostConnected = false,
                    hostPid = null,
                    overlayPermissionGranted = true,
                    crossWindowBlurSupported = true,
                    gestureMonitorActive = true,
                    taskListenerActive = true,
                    panelState = PanelState.CLOSED,
                    screenWidthPx = 1080,
                    screenHeightPx = 2400,
                    density = 3.0f,
                    wsPort = 12345,
                )

            override fun currentConfigJson(): JsonElement = SideveilWireJson.encodeToJsonElement(SideveilConfig.DEFAULT)

            override fun reloadConfig(): JsonElement = currentConfigJson()

            override fun patchAppearance(params: AppearancePatchParams): JsonElement = currentConfigJson()

            override fun sendPanelCommand(command: String) {}

            override fun restartHost() {}

            override fun onHostHello(payload: HostHelloPayload) {}

            override fun onPanelStateChanged(state: PanelState) {}
        }

    @Test
    fun `configStore creates default config, patches appearance with sanitization, and preserves valid config on corrupt reload`() {
        val testDir = tempFolder.newFolder("config-store-test")
        val configFile = File(testDir, "config.json")
        assertFalse(configFile.exists())

        var notifiedConfig: SideveilConfig? = null
        val store =
            ConfigStore(configFile = configFile) { cfg ->
                notifiedConfig = cfg
            }

        store.start()
        try {
            assertTrue(configFile.exists())
            assertEquals(SideveilConfig.DEFAULT, store.current)
            assertEquals(SideveilConfig.DEFAULT, notifiedConfig)

            val patch =
                AppearancePatchParams(
                    blurRadiusPx = 9999,
                    cornerRadiusDp = -10,
                    themeId = "unsupported-custom-theme",
                    colorMode = "dark",
                )
            val updated = store.updateAppearance(patch)
            assertEquals(300, updated.panel.blurRadiusPx)
            assertEquals(8, updated.panel.cornerRadiusDp)
            assertEquals("coloros-glass", updated.panel.themeId)
            assertEquals("dark", updated.panel.colorMode)
            assertEquals(updated, store.current)
            assertEquals(updated, notifiedConfig)

            val persistedJson = configFile.readText(StandardCharsets.UTF_8)
            val persistedConfig = SideveilPrettyJson.decodeFromString<SideveilConfig>(persistedJson)
            assertEquals(updated, persistedConfig)

            val lastValidConfig = store.current
            configFile.writeText("{ corrupt json content :::: ", StandardCharsets.UTF_8)
            val reloaded = store.reloadFromDisk(notifyIfUnchanged = true)
            assertEquals(lastValidConfig, reloaded)
            assertEquals(lastValidConfig, store.current)
        } finally {
            store.stop()
        }
    }

    @Test
    fun `controlServer starts on ephemeral loopback port, writes daemon env file, and handles status authorization`() {
        val runDir = tempFolder.newFolder("server-status-test")
        val delegate = createTestRpcDelegate()
        val server =
            ControlServer(
                runDir = runDir,
                uptimeProvider = { 12345L },
                delegate = delegate,
            )

        server.start()
        try {
            assertTrue("Bound port must be ephemeral loopback (> 0)", server.boundPort > 0)

            val envFile = File(runDir, "daemon.env")
            assertTrue("daemon.env file must exist in runDir", envFile.exists())
            val envText = envFile.readText(StandardCharsets.UTF_8)
            assertTrue(envText.contains("SIDEVEIL_PORT=${server.boundPort}"))
            assertTrue(envText.contains("SIDEVEIL_TOKEN=${server.sessionToken}"))

            val unauthConn = URL("http://127.0.0.1:${server.boundPort}/status").openConnection() as HttpURLConnection
            unauthConn.connectTimeout = 3000
            unauthConn.readTimeout = 3000
            unauthConn.requestMethod = "GET"
            assertEquals(401, unauthConn.responseCode)

            val authConn = URL("http://127.0.0.1:${server.boundPort}/status").openConnection() as HttpURLConnection
            authConn.connectTimeout = 3000
            authConn.readTimeout = 3000
            authConn.requestMethod = "GET"
            authConn.setRequestProperty("Authorization", "Bearer ${server.sessionToken}")
            assertEquals(200, authConn.responseCode)

            val body = authConn.inputStream.bufferedReader().use { it.readText() }
            val status = SideveilWireJson.decodeFromString<DaemonStatus>(body)
            assertEquals(4242, status.pid)
            assertEquals("com.test.pkg", status.foregroundPackage)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `controlServer rejects untrusted origin and completes websocket upgrade and daemon ping`() {
        val runDir = tempFolder.newFolder("server-ws-test")
        val delegate = createTestRpcDelegate()
        val server =
            ControlServer(
                runDir = runDir,
                uptimeProvider = { 778899L },
                delegate = delegate,
            )

        server.start()
        try {
            Socket("127.0.0.1", server.boundPort).use { sock ->
                sock.soTimeout = 3000
                val out = sock.getOutputStream()
                val input = sock.getInputStream()
                val forbiddenHandshake =
                    buildString {
                        append("GET /ws HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:${server.boundPort}\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                        append("Sec-WebSocket-Version: 13\r\n")
                        append("Authorization: Bearer ").append(server.sessionToken).append("\r\n")
                        append("Origin: https://evil.example\r\n")
                        append("\r\n")
                    }
                out.write(forbiddenHandshake.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val block = WebSocketCodec.readHttpHeaderBlock(input)
                assertNotNull(block)
                assertTrue("Expected 403 Forbidden but got ${block!!.requestLine}", block.requestLine.contains("403"))
            }

            Socket("127.0.0.1", server.boundPort).use { sock ->
                sock.soTimeout = 5000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                WebSocketCodec.performClientHandshake(
                    input = input,
                    output = output,
                    path = "/ws",
                    hostHeader = "127.0.0.1:${server.boundPort}",
                    extraHeaders = mapOf("Authorization" to "Bearer ${server.sessionToken}"),
                )

                val rpcReq = RpcRequest(id = "rpc-ping-1", method = SideveilMethods.DAEMON_PING)
                WebSocketCodec.writeText(output, SideveilWireJson.encodeToString(rpcReq), mask = true)

                var pingResponse: RpcResponse? = null
                val deadline = System.currentTimeMillis() + 5000L
                while (System.currentTimeMillis() < deadline && pingResponse == null) {
                    val frame = WebSocketCodec.readFrame(input) ?: break
                    val text = frame.textOrNull() ?: continue
                    val parsed = runCatching { SideveilWireJson.decodeFromString<RpcResponse>(text) }.getOrNull()
                    if (parsed?.id == "rpc-ping-1") {
                        pingResponse = parsed
                    }
                }

                assertNotNull("Expected daemon.ping response", pingResponse)
                assertNull(pingResponse!!.error)
                val pingResult = SideveilWireJson.decodeFromJsonElement<PingResult>(pingResponse.result!!)
                assertTrue(pingResult.pong)
                assertEquals(778899L, pingResult.daemonUptimeMs)
            }
        } finally {
            server.stop()
        }
    }
}
