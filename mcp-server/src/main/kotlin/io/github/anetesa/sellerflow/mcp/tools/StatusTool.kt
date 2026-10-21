package io.github.anetesa.sellerflow.mcp.tools

import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

/**
 * `sellerflow_status`: which environment is active and which services are set up. It reports only
 * whether a setting is present, never its value, so it is safe to show to the agent.
 */
internal fun Server.registerStatusTool(config: SellerFlowConfig) {
    addTool(
        name = "sellerflow_status",
        description =
            "Shows whether SellerFlow works against the sandbox or production and which services (Allegro, InPost, KSeF) " +
                "are configured. Read-only and free; call it first when a tool reports a configuration problem.",
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) {
        CallToolResult(content = listOf(TextContent(statusText(config))))
    }
}

internal fun statusText(config: SellerFlowConfig): String =
    buildString {
        appendLine(
            when (config.environment) {
                Environment.SANDBOX -> "Environment: sandbox (test accounts, no real orders or invoices)"
                Environment.PROD -> "Environment: PRODUCTION (real orders, real shipments, legally binding invoices)"
            },
        )
        appendLine("Allegro: " + if (config.allegro.clientId != null) "client id set" else "not configured")
        appendLine("InPost: " + if (config.inpost.organizationId != null) "organization id set" else "not configured")
        append("KSeF: not available yet")
    }
