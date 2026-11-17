package io.github.anetesa.sellerflow.core.config

/**
 * Which set of services SellerFlow talks to. Sandbox is the default; production is chosen only by
 * setting `SELLERFLOW_ENV=prod` in the environment, never by the config file, so a copied config
 * cannot switch a test setup to real orders and real invoices by accident.
 */
enum class Environment(
    val endpoints: Endpoints,
) {
    SANDBOX(
        Endpoints(
            allegroApi = "https://api.allegro.pl.allegrosandbox.pl",
            allegroAuth = "https://allegro.pl.allegrosandbox.pl/auth/oauth",
            inpostApi = "https://sandbox-api-shipx-pl.easypack24.net",
            ksefApi = "https://api-test.ksef.mf.gov.pl/v2",
        ),
    ),
    PROD(
        Endpoints(
            allegroApi = "https://api.allegro.pl",
            allegroAuth = "https://allegro.pl/auth/oauth",
            inpostApi = "https://api-shipx-pl.easypack24.net",
            ksefApi = "https://api.ksef.mf.gov.pl/v2",
        ),
    ),
    ;

    companion object {
        /** The environment named by `SELLERFLOW_ENV`; null for an unknown name. */
        fun fromEnvValue(value: String?): Environment? =
            when (value?.trim()?.lowercase()) {
                null, "", "sandbox" -> SANDBOX
                "prod" -> PROD
                else -> null
            }
    }
}

/** Base URLs of the services; the KSeF ones come from the Ministry of Finance's srodowiska.md (TEST and PROD). */
data class Endpoints(
    val allegroApi: String,
    val allegroAuth: String,
    val inpostApi: String,
    val ksefApi: String,
)
