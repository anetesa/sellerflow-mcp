package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.core.config.AllegroSettings
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.config.InpostSettings
import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Path

// The in-memory transport is marked experimental in the SDK; it is only used here, in tests
@OptIn(ExperimentalMcpApi::class)
class SellerFlowServerTest :
    FunSpec({
        val config =
            SellerFlowConfig(
                environment = Environment.SANDBOX,
                dataDir = Path.of("/tmp/sellerflow-test"),
                allegro = AllegroSettings(clientId = "client-123"),
                inpost = InpostSettings(organizationId = null),
            )

        suspend fun connectedClient(): Client {
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            SellerFlowServer.create(config).createSession(serverTransport)
            return Client(Implementation("test", "1.0")).apply { connect(clientTransport) }
        }

        test("answers ping and introduces itself") {
            val client = connectedClient()
            client.ping()
            client.serverVersion?.name shouldBe SellerFlowServer.NAME
            client.close()
        }

        test("status tool reports the environment without leaking setting values") {
            val client = connectedClient()
            client.listTools().tools.map { it.name } shouldContain "sellerflow_status"
            val text = (client.callTool("sellerflow_status", emptyMap()).content.single() as TextContent).text
            text shouldContain "sandbox"
            text shouldContain "Allegro: client id set"
            text shouldContain "InPost: not configured"
            text shouldNotContain "client-123"
            client.close()
        }
    })
