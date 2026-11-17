package io.github.anetesa.sellerflow.core.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.github.anetesa.sellerflow.core.model.Address
import io.github.anetesa.sellerflow.core.model.Nip
import io.github.anetesa.sellerflow.core.model.SellerProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds [SellerFlowConfig] from `sellerflow.toml` (optional) and environment variables, which win.
 *
 * The file is looked up at `SELLERFLOW_CONFIG`, then `./sellerflow.toml`. It must not contain
 * secrets: keys that look like one are rejected with a message saying which variable to use instead.
 */
class ConfigLoader(
    private val env: (String) -> String? = System::getenv,
    private val workingDir: Path = Path.of(""),
    private val home: Path = Path.of(System.getProperty("user.home")),
) {
    fun load(): Outcome<SellerFlowConfig> {
        val environment =
            Environment.fromEnvValue(env(ENV))
                ?: return error("$ENV must be 'sandbox' or 'prod', got '${env(ENV)}'")
        return readFile().flatMap { file -> seller(file.seller).map { seller -> file to seller } }.map { (file, seller) ->
            SellerFlowConfig(
                environment = environment,
                dataDir = expandHome(env(DATA_DIR) ?: file.dataDir ?: DEFAULT_DATA_DIR),
                allegro =
                    AllegroSettings(
                        clientId = env(ALLEGRO_CLIENT_ID) ?: file.allegro.clientId,
                        userAgent = env(ALLEGRO_USER_AGENT) ?: file.allegro.userAgent,
                    ),
                inpost = InpostSettings(organizationId = env(INPOST_ORGANIZATION_ID) ?: file.inpost.organizationId),
                seller = seller,
            )
        }
    }

    /** The [seller] section, checked: a NIP with a valid checksum, an address, a VAT rate SellerFlow can invoice. */
    private fun seller(section: SellerSection?): Outcome<SellerSettings?> {
        if (section == null) return Outcome.Ok(null)
        val nip = Nip.parse(section.nip)
        val missing = listOf("name" to section.name, "street" to section.street, "post_code" to section.postCode, "city" to section.city)
        return when {
            nip == null -> error("[seller] nip '${section.nip}' is not a valid NIP (10 digits with a checksum).")
            missing.any { it.second.isBlank() } ->
                error(
                    "[seller] needs ${missing.filter { it.second.isBlank() }.joinToString { it.first }}.",
                )
            section.vatRate !in SUPPORTED_RATES -> error("[seller] vat_rate must be one of $SUPPORTED_RATES.")
            else ->
                Outcome.Ok(
                    SellerSettings(
                        profile =
                            SellerProfile(
                                name = section.name,
                                nip = nip,
                                address = Address(section.street, section.postCode, section.city, section.countryCode),
                                email = section.email,
                                bankAccount = null,
                            ),
                        vatRate = section.vatRate,
                        invoicePrefix = section.invoicePrefix,
                    ),
                )
        }
    }

    private fun readFile(): Outcome<FileConfig> {
        val explicit = env(CONFIG)
        val path = explicit?.let(Path::of) ?: workingDir.resolve(DEFAULT_FILE)
        return when {
            Files.isRegularFile(path) -> parse(path)
            explicit != null -> error("Config file $path from $CONFIG does not exist")
            else -> Outcome.Ok(FileConfig())
        }
    }

    private fun parse(path: Path): Outcome<FileConfig> {
        val text = Files.readString(path)
        SECRET_KEY.find(text)?.let {
            return error(
                "$path contains '${it.groupValues[1]}': secrets do not belong in the config file. " +
                    "Set them as environment variables (${Secrets.ALLEGRO_CLIENT_SECRET}, ${Secrets.INPOST_API_TOKEN}).",
            )
        }
        return try {
            Outcome.Ok(TOML.decodeFromString(FileConfig.serializer(), text))
        } catch (e: SerializationException) {
            error("Cannot read $path: ${e.message}")
        } catch (e: IllegalArgumentException) {
            error("Cannot read $path: ${e.message}")
        }
    }

    private fun expandHome(path: String): Path = if (path.startsWith("~/")) home.resolve(path.removePrefix("~/")) else Path.of(path)

    private fun error(message: String) = Outcome.Err(SellerFlowError.Config(message))

    @Serializable
    private data class FileConfig(
        @SerialName("data_dir") val dataDir: String? = null,
        val allegro: AllegroSection = AllegroSection(),
        val inpost: InpostSection = InpostSection(),
        val seller: SellerSection? = null,
    )

    @Serializable
    private data class SellerSection(
        val name: String = "",
        val nip: String = "",
        val street: String = "",
        @SerialName("post_code") val postCode: String = "",
        val city: String = "",
        @SerialName("country_code") val countryCode: String = "PL",
        val email: String? = null,
        @SerialName("vat_rate") val vatRate: Int = DEFAULT_RATE,
        @SerialName("invoice_prefix") val invoicePrefix: String = "FV",
    )

    @Serializable
    private data class AllegroSection(
        @SerialName("client_id") val clientId: String? = null,
        @SerialName("user_agent") val userAgent: String? = null,
    )

    @Serializable
    private data class InpostSection(
        @SerialName("organization_id") val organizationId: String? = null,
    )

    companion object {
        const val ENV = "SELLERFLOW_ENV"
        const val CONFIG = "SELLERFLOW_CONFIG"
        const val DATA_DIR = "SELLERFLOW_DATA_DIR"
        const val ALLEGRO_CLIENT_ID = "SELLERFLOW_ALLEGRO_CLIENT_ID"
        const val ALLEGRO_USER_AGENT = "SELLERFLOW_ALLEGRO_USER_AGENT"
        const val INPOST_ORGANIZATION_ID = "SELLERFLOW_INPOST_ORGANIZATION_ID"
        private const val DEFAULT_FILE = "sellerflow.toml"
        private const val DEFAULT_RATE = 23
        private val SUPPORTED_RATES = setOf(23, 22, 8, 7, 5)
        private const val DEFAULT_DATA_DIR = "~/.sellerflow"
        private val SECRET_KEY = Regex("""(?im)^\s*([a-z_]*(?:secret|token|password|api_key)[a-z_]*)\s*=""")
        private val TOML = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))
    }
}
