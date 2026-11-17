package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.github.anetesa.sellerflow.mcp.tools.AllegroTools
import io.github.anetesa.sellerflow.mcp.tools.InpostTools
import io.github.anetesa.sellerflow.mcp.tools.KsefTools
import io.github.anetesa.sellerflow.mcp.tools.registerAllegroTools
import io.github.anetesa.sellerflow.mcp.tools.registerInpostTools
import io.github.anetesa.sellerflow.mcp.tools.registerKsefTools
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

    /**
     * [allegro] is null when Allegro is not set up; its tools then answer with [allegroProblem], which says what is
     * missing, so the agent can tell the seller instead of guessing.
     */
    fun create(
        config: SellerFlowConfig,
        allegro: AllegroTools? = null,
        allegroProblem: String = "Allegro is not set up.",
        inpost: InpostTools? = null,
        inpostProblem: String = "InPost is not set up.",
        ksef: KsefTools? = null,
        ksefProblem: String = "KSeF is not set up.",
    ): Server =
        Server(
            serverInfo = Implementation(name = NAME, version = VERSION, title = "SellerFlow"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            instructions = INSTRUCTIONS,
        ) {
            registerStatusTool(config)
            registerAllegroTools(allegro, allegroProblem)
            registerInpostTools(inpost, inpostProblem)
            registerKsefTools(ksef, ksefProblem)
        }
}
