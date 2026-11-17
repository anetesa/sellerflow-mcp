package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.allegro.AllegroAuth
import io.github.anetesa.sellerflow.allegro.AllegroHttp
import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.allegro.AllegroSession
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.Secrets
import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.github.anetesa.sellerflow.core.store.CredentialStore
import io.github.anetesa.sellerflow.core.store.InMemoryCredentialStore
import io.github.anetesa.sellerflow.inpost.InpostHttp
import io.github.anetesa.sellerflow.inpost.InpostShipments
import io.github.anetesa.sellerflow.mcp.confirm.ConfirmTokens
import io.github.anetesa.sellerflow.mcp.tools.AllegroTools
import io.github.anetesa.sellerflow.mcp.tools.InpostTools
import io.github.anetesa.sellerflow.storage.SqliteCredentialStore
import io.github.anetesa.sellerflow.storage.SqliteShipmentLedger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/** Builds the real services from the configuration; problems become messages instead of crashes. */
internal class Wiring(
    private val config: SellerFlowConfig,
    private val client: HttpClient,
    private val secrets: Secrets = Secrets(),
    private val warn: (String) -> Unit = System.err::println,
) {
    /** The encrypted database in the data dir, or memory (logins lost on exit) when SELLERFLOW_STORE_KEY is not set. */
    val store: CredentialStore by lazy {
        val key = secrets.storeKey()
        if (key == null) {
            warn("SELLERFLOW_STORE_KEY is not set: logins are kept in memory only and lost when SellerFlow stops.")
            InMemoryCredentialStore()
        } else {
            when (val opened = SqliteCredentialStore.open(config.dataDir.resolve("sellerflow.db"), key)) {
                is Outcome.Ok -> opened.value
                is Outcome.Err -> error(opened.error.message)
            }
        }
    }

    fun allegroHttp(): Outcome<AllegroHttp> =
        AllegroHttp.create(
            client = client,
            endpoints = config.endpoints,
            clientId = config.allegro.clientId,
            clientSecret = secrets.allegroClientSecret(),
            userAgent = config.allegro.userAgent,
        )

    fun allegroSession(http: AllegroHttp) = AllegroSession(AllegroAuth(http), store)

    /** The seller's orders, when Allegro is set up. */
    private val allegroOrders: AllegroOrders? by lazy {
        (allegroHttp() as? Outcome.Ok)?.value?.let { http ->
            val session = allegroSession(http)
            AllegroOrders(http) { session.accessToken() }
        }
    }

    /** The Allegro tools, or the reason they cannot work. */
    fun allegroTools(scope: CoroutineScope): Pair<AllegroTools?, String> =
        when (val http = allegroHttp()) {
            is Outcome.Err -> null to http.error.message
            is Outcome.Ok -> {
                val session = allegroSession(http.value)
                AllegroTools(AllegroAuth(http.value), session, AllegroOrders(http.value) { session.accessToken() }, scope) to ""
            }
        }

    /** The InPost tools, or the reason they cannot work. Shipments are recorded per order in the local database. */
    fun inpostTools(): Pair<InpostTools?, String> =
        when (
            val http =
                InpostHttp.create(client, config.endpoints.inpostApi, config.inpost.organizationId, secrets.inpostApiToken())
        ) {
            is Outcome.Err -> null to http.error.message
            is Outcome.Ok ->
                InpostTools(
                    shipments = InpostShipments(http.value),
                    orders = allegroOrders,
                    ledger = SqliteShipmentLedger.open(config.dataDir.resolve("sellerflow.db")),
                    tokens = ConfirmTokens(),
                    labelsDir = config.dataDir.resolve("labels"),
                ) to ""
        }
}
