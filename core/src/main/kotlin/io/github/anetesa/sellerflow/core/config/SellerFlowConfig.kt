package io.github.anetesa.sellerflow.core.config

import java.nio.file.Path

/**
 * Everything SellerFlow needs to know that is not a secret. Secrets (client secrets, API tokens,
 * the storage key) come only from environment variables, see [Secrets].
 */
data class SellerFlowConfig(
    val environment: Environment,
    /** Where the local database (tokens, order mappings, audit log) and downloaded labels live. */
    val dataDir: Path,
    val allegro: AllegroSettings,
    val inpost: InpostSettings,
) {
    val endpoints: Endpoints get() = environment.endpoints
}

data class AllegroSettings(
    /** The client id of the app registered on developer.allegro.pl; not a secret. */
    val clientId: String?,
    /**
     * The User-Agent Allegro generated for the app, e.g. `SellerFlow/1.0 (+https://github.com/anetesa/sellerflow-mcp)`.
     * Allegro blocks the API key of an app that calls without it, so no request is sent while it is missing.
     */
    val userAgent: String? = null,
)

data class InpostSettings(
    /** ShipX organization id, shown in the InPost parcel manager next to the API token. */
    val organizationId: String?,
)

/** Secrets, read from the environment at the moment they are needed and never stored in config objects. */
class Secrets(
    private val env: (String) -> String? = System::getenv,
) {
    fun allegroClientSecret(): String? = env(ALLEGRO_CLIENT_SECRET)?.takeIf { it.isNotBlank() }

    fun inpostApiToken(): String? = env(INPOST_API_TOKEN)?.takeIf { it.isNotBlank() }

    /** Key that encrypts tokens kept in the local database. */
    fun storeKey(): String? = env(STORE_KEY)?.takeIf { it.isNotBlank() }

    companion object {
        const val ALLEGRO_CLIENT_SECRET = "SELLERFLOW_ALLEGRO_CLIENT_SECRET"
        const val INPOST_API_TOKEN = "SELLERFLOW_INPOST_API_TOKEN"
        const val STORE_KEY = "SELLERFLOW_STORE_KEY"
    }
}
