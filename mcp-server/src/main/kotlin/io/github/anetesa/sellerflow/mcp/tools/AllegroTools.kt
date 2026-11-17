package io.github.anetesa.sellerflow.mcp.tools

import io.github.anetesa.sellerflow.allegro.AllegroAuth
import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.allegro.AllegroSession
import io.github.anetesa.sellerflow.allegro.DeviceCode
import io.github.anetesa.sellerflow.allegro.OrderQuery
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.model.Order
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What the Allegro tools need; null pieces mean Allegro is not configured and the tools explain why. */
class AllegroTools(
    private val auth: AllegroAuth,
    private val session: AllegroSession,
    private val orders: AllegroOrders,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var pending: PendingLogin? = null

    /** A login waiting for the seller's approval; [failure] is set by the background poll if it ends badly. */
    private class PendingLogin(
        val code: DeviceCode,
    ) {
        @Volatile var failure: SellerFlowError? = null

        @Volatile var done = false
    }

    internal suspend fun login(): String =
        mutex.withLock {
            val current = pending
            when {
                session.isLoggedIn -> {
                    pending = null
                    "Logged in to Allegro. The login is saved and refreshed automatically."
                }
                current != null && current.failure != null -> {
                    pending = null
                    "The last login failed: ${current.failure!!.message} Call allegro_login again to get a new link."
                }
                current != null && !current.done -> waiting(current.code)
                else -> start()
            }
        }

    private suspend fun start(): String =
        when (val started = auth.startDeviceFlow()) {
            is Outcome.Err -> started.error.message
            is Outcome.Ok -> {
                val login = PendingLogin(started.value)
                pending = login
                scope.launch {
                    when (val result = auth.awaitAuthorization(login.code)) {
                        is Outcome.Ok -> session.save(result.value)
                        is Outcome.Err -> login.failure = result.error
                    }
                    login.done = true
                }
                waiting(login.code)
            }
        }

    private fun waiting(code: DeviceCode) =
        "Ask the seller to open ${code.verificationUriComplete} (logged in to the right Allegro account) and approve " +
            "SellerFlow; the code on the page is ${code.userCode}. Then call allegro_login again to confirm. " +
            "The link works for ${code.expiresInSeconds / SECONDS_PER_MINUTE} minutes."

    internal suspend fun listOrders(query: OrderQuery): String =
        when (val result = orders.list(query)) {
            is Outcome.Err -> result.error.message
            is Outcome.Ok ->
                if (result.value.isEmpty()) {
                    "No Allegro orders match (paid and not yet sent, unless other statuses were asked for)."
                } else {
                    "${result.value.size} order(s):\n\n" + result.value.joinToString("\n\n") { it.describe() }
                }
        }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}

internal fun Order.describe(): String =
    buildString {
        appendLine("Order $id (placed $createdAt, $status), total $total")
        appendLine(
            "  Buyer: ${buyer.login}" +
                listOfNotNull(buyer.firstName, buyer.lastName).joinToString(" ", prefix = ", ").takeIf { it.length > 2 }.orEmpty(),
        )
        appendLine(
            "  Delivery: ${delivery.methodName.ifBlank { "unknown" }} (${delivery.method}" +
                (delivery.pickupPointId?.let { ", locker $it" } ?: "") +
                ")",
        )
        lines.forEach { appendLine("  ${it.quantity} × ${it.name} (offer ${it.offerId}) at ${it.unitPrice}") }
        append(
            "  Invoice: " +
                when {
                    invoiceRequest == null -> "not requested"
                    invoiceRequest!!.nip != null -> "${invoiceRequest!!.name}, NIP ${invoiceRequest!!.nip}"
                    else -> "${invoiceRequest!!.name}, no valid NIP (private person, or ask the buyer)"
                },
        )
    }

/** Registers allegro_login and allegro_list_orders; with [tools] null they explain what is missing in the setup. */
internal fun Server.registerAllegroTools(
    tools: AllegroTools?,
    notConfigured: String,
) {
    addTool(
        name = "allegro_login",
        description =
            "Connects SellerFlow to the seller's Allegro account. The first call returns a link the seller opens to " +
                "approve access; a later call confirms the login. When already logged in it says so and changes nothing.",
        toolAnnotations = ToolAnnotations(title = "Log in to Allegro", readOnlyHint = false, destructiveHint = false, openWorldHint = true),
    ) {
        CallToolResult(content = listOf(TextContent(tools?.login() ?: notConfigured)))
    }
    addTool(
        name = "allegro_list_orders",
        description =
            "Lists the seller's Allegro orders that are paid and not yet sent: buyer, items, total, delivery method " +
                "and InPost locker, and the invoice data if the buyer asked for an invoice. Read-only.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("updated_from") {
                            put("type", "string")
                            put("description", "Only orders changed at or after this ISO 8601 time, e.g. 2026-11-16T00:00:00Z")
                        }
                        putJsonObject("max") {
                            put("type", "integer")
                            put("description", "At most this many orders (default 50, up to 500)")
                        }
                    },
            ),
        toolAnnotations = ToolAnnotations(title = "List Allegro orders", readOnlyHint = true, openWorldHint = true),
    ) { request ->
        val args = request.arguments
        val query =
            OrderQuery(
                updatedFrom = args?.get("updated_from")?.jsonPrimitive?.contentOrNull,
                max = (args?.get("max")?.jsonPrimitive?.intOrNull ?: DEFAULT_LIST).coerceIn(1, OrderQuery.DEFAULT_MAX),
            )
        CallToolResult(content = listOf(TextContent(tools?.listOrders(query) ?: notConfigured)))
    }
}

private const val DEFAULT_LIST = 50
