package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.github.anetesa.sellerflow.mcp.tools.registerStatusTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/** The MCP server with every SellerFlow tool registered; transports are attached by the caller. */
object SellerFlowServer {
    const val NAME = "sellerflow"
    const val VERSION = "0.1.0"

    private val INSTRUCTIONS =
        """
        SellerFlow runs the daily fulfillment of a Polish online seller: Allegro orders, InPost shipments and labels,
        invoices in KSeF. Read tools are free to call. Every tool that changes something (creates a shipment, sends an
        invoice) first returns a preview with a confirm_token; show the preview to the user and call again with
        dry_run=false and that token only after they agree. Call sellerflow_status first if unsure what is configured.
        """.trimIndent()

    fun create(config: SellerFlowConfig): Server =
        Server(
            serverInfo = Implementation(name = NAME, version = VERSION, title = "SellerFlow"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            instructions = INSTRUCTIONS,
        ) {
            registerStatusTool(config)
        }
}
