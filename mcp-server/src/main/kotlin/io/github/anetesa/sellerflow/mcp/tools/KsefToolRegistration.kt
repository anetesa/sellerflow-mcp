package io.github.anetesa.sellerflow.mcp.tools

import io.github.anetesa.sellerflow.core.model.OrderId
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Registers ksef_issue_invoice; with [tools] null it explains what is missing in the setup. */
internal fun Server.registerKsefTools(
    tools: KsefTools?,
    notConfigured: String,
) {
    addTool(
        name = "ksef_issue_invoice",
        description =
            "Issues the FA(3) VAT invoice of an Allegro order whose buyer asked for one, in KSeF. With dry_run=true (the " +
                "default) it returns a preview (number, buyer, lines, VAT) and a confirm_token; with dry_run=false and that " +
                "token it sends the invoice and returns the KSeF number. An order never gets a second invoice.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("order_id") {
                            put("type", "string")
                            put("description", "Allegro order (checkout form) id")
                        }
                        putJsonObject("dry_run") {
                            put("type", "boolean")
                            put("description", "true (default): preview only. false: send to KSeF; needs confirm_token")
                        }
                        putJsonObject("confirm_token") {
                            put("type", "string")
                            put("description", "The token from the preview, after the seller agreed")
                        }
                    },
                required = listOf("order_id"),
            ),
        toolAnnotations =
            ToolAnnotations(
                title = "Issue a KSeF invoice",
                readOnlyHint = false,
                destructiveHint = false,
                openWorldHint = true,
            ),
    ) { request ->
        val args = request.arguments
        val text =
            tools?.issueInvoice(
                orderId =
                    OrderId(
                        args
                            ?.get("order_id")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty(),
                    ),
                dryRun = args?.get("dry_run")?.jsonPrimitive?.booleanOrNull ?: true,
                confirmToken = args?.get("confirm_token")?.jsonPrimitive?.contentOrNull,
            ) ?: notConfigured
        CallToolResult(content = listOf(TextContent(text)))
    }
}
