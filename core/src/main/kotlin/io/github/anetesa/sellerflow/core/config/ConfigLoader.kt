package io.github.anetesa.sellerflow.core.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.map
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
        return readFile().map { file ->
            SellerFlowConfig(
                environment = environment,
                dataDir = expandHome(env(DATA_DIR) ?: file.dataDir ?: DEFAULT_DATA_DIR),
                allegro = AllegroSettings(clientId = env(ALLEGRO_CLIENT_ID) ?: file.allegro.clientId),
                inpost = InpostSettings(organizationId = env(INPOST_ORGANIZATION_ID) ?: file.inpost.organizationId),
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
    )

    @Serializable
    private data class AllegroSection(
        @SerialName("client_id") val clientId: String? = null,
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
        const val INPOST_ORGANIZATION_ID = "SELLERFLOW_INPOST_ORGANIZATION_ID"
        private const val DEFAULT_FILE = "sellerflow.toml"
        private const val DEFAULT_DATA_DIR = "~/.sellerflow"
        private val SECRET_KEY = Regex("""(?im)^\s*([a-z_]*(?:secret|token|password|api_key)[a-z_]*)\s*=""")
        private val TOML = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))
    }
}
