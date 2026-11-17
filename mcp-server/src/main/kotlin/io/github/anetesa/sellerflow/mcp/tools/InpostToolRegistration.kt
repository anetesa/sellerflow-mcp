package io.github.anetesa.sellerflow.mcp.tools

import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.ParcelSize
import io.github.anetesa.sellerflow.core.model.ShipmentId
import io.github.anetesa.sellerflow.inpost.LabelFormat
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Registers the InPost tools; with [tools] null they explain what is missing in the setup. */
internal fun Server.registerInpostTools(
    tools: InpostTools?,
    notConfigured: String,
) {
    registerFindLocker(tools, notConfigured)
    registerCreateShipment(tools, notConfigured)
    registerGetLabel(tools, notConfigured)
}

private fun Server.registerFindLocker(
    tools: InpostTools?,
    notConfigured: String,
) {
    addTool(
        name = "inpost_find_locker",
        description =
            "Looks up an InPost parcel locker by its name (e.g. KRA010): whether it operates, its address and where it is. " +
                "Read-only.",
        inputSchema = schema(listOf("name")) { string("name", "Locker name, e.g. KRA010") },
        toolAnnotations = ToolAnnotations(title = "Find an InPost locker", readOnlyHint = true, openWorldHint = true),
    ) { request ->
        reply(tools?.findLocker(request.string("name").orEmpty()) ?: notConfigured)
    }
}

private fun Server.registerCreateShipment(
    tools: InpostTools?,
    notConfigured: String,
) {
    addTool(
        name = "inpost_create_shipment",
        description =
            "Ships an Allegro order to the InPost locker its buyer chose. With dry_run=true (the default) it only returns a " +
                "preview and a confirm_token; with dry_run=false and that token it creates the shipment, which InPost charges " +
                "to the seller. An order never gets a second shipment.",
        inputSchema =
            schema(listOf("order_id", "size")) {
                string("order_id", "Allegro order (checkout form) id")
                putJsonObject("size") {
                    put("type", "string")
                    put("enum", buildJsonArray { ParcelSize.entries.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } })
                    put("description", "Locker size: A (up to 8 cm high), B (19 cm) or C (41 cm)")
                }
                putJsonObject("dry_run") {
                    put("type", "boolean")
                    put("description", "true (default): preview only. false: create the shipment; needs confirm_token")
                }
                string("confirm_token", "The token from the preview, after the seller agreed")
            },
        toolAnnotations =
            ToolAnnotations(
                title = "Create an InPost shipment",
                readOnlyHint = false,
                destructiveHint = false,
                openWorldHint = true,
            ),
    ) { request ->
        val size = request.string("size")?.uppercase()?.let { s -> ParcelSize.entries.firstOrNull { it.name == s } }
        reply(
            when {
                tools == null -> notConfigured
                size == null -> "size must be A, B or C."
                else ->
                    tools.createShipment(
                        orderId = OrderId(request.string("order_id").orEmpty()),
                        size = size,
                        dryRun =
                            request.arguments
                                ?.get("dry_run")
                                ?.jsonPrimitive
                                ?.booleanOrNull ?: true,
                        confirmToken = request.string("confirm_token"),
                    )
            },
        )
    }
}

private fun Server.registerGetLabel(
    tools: InpostTools?,
    notConfigured: String,
) {
    addTool(
        name = "inpost_get_label",
        description =
            "Saves the PDF label of a confirmed InPost shipment on this computer and returns the file path. A6 by default " +
                "(label printers); A4 for office printers.",
        inputSchema =
            schema(listOf("shipment_id")) {
                string("shipment_id", "InPost shipment id")
                putJsonObject("format") {
                    put("type", "string")
                    put("enum", buildJsonArray { LabelFormat.entries.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } })
                }
            },
        toolAnnotations =
            ToolAnnotations(
                title = "Save an InPost label",
                readOnlyHint = false,
                destructiveHint = false,
                openWorldHint = true,
            ),
    ) { request ->
        val format = request.string("format")?.uppercase()?.let { f -> LabelFormat.entries.firstOrNull { it.name == f } } ?: LabelFormat.A6
        reply(tools?.label(ShipmentId(request.string("shipment_id").orEmpty()), format) ?: notConfigured)
    }
}

private fun reply(text: String) = CallToolResult(content = listOf(TextContent(text)))

private fun CallToolRequest.string(name: String): String? =
    arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

private fun schema(
    required: List<String>,
    properties: JsonObjectBuilder.() -> Unit,
) = ToolSchema(properties = buildJsonObject(properties), required = required)

private fun JsonObjectBuilder.string(
    name: String,
    description: String,
) = putJsonObject(name) {
    put("type", "string")
    put("description", description)
}
