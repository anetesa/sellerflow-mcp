package io.github.anetesa.sellerflow.storage

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.store.CredentialStore
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Credentials in SQLite, each value encrypted with AES-256-GCM. The key is derived from SELLERFLOW_STORE_KEY with
 * PBKDF2 and a random salt kept in the database; a known value encrypted with it tells a wrong key apart from a
 * damaged database. The name of each value is bound into its ciphertext, so values cannot be swapped between names.
 */
class SqliteCredentialStore private constructor(
    private val connection: Connection,
    private val key: SecretKey,
) : CredentialStore,
    AutoCloseable {
    @Synchronized
    override fun get(name: String): String? =
        connection.prepareStatement("SELECT nonce, ciphertext FROM credentials WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { row ->
                if (row.next()) decrypt(key, name, row.getBytes(1), row.getBytes(2)) else null
            }
        }

    @Synchronized
    override fun put(
        name: String,
        value: String,
    ) {
        val (nonce, ciphertext) = encrypt(key, name, value)
        connection
            .prepareStatement(
                """
                INSERT INTO credentials (name, nonce, ciphertext, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (name) DO UPDATE SET nonce = excluded.nonce, ciphertext = excluded.ciphertext,
                updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { statement ->
                listOf<Any>(name, nonce, ciphertext).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
    }

    @Synchronized
    override fun remove(name: String) {
        connection.prepareStatement("DELETE FROM credentials WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeUpdate()
        }
    }

    override fun close() = connection.close()

    companion object {
        private const val CHECK_NAME = "__key_check__"
        private const val CHECK_VALUE = "sellerflow"
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val ITERATIONS = 310_000
        private const val MIN_KEY_LENGTH = 16

        /** Opens (or creates) the store at [file]; a wrong [storeKey] is reported, not silently ignored. */
        fun open(
            file: Path,
            storeKey: String?,
        ): Outcome<SqliteCredentialStore> {
            if (storeKey == null || storeKey.length < MIN_KEY_LENGTH) {
                return Outcome.Err(
                    SellerFlowError.Config(
                        "SELLERFLOW_STORE_KEY must be set to at least $MIN_KEY_LENGTH characters to keep logins between runs " +
                            "(for example the output of `openssl rand -base64 32`).",
                    ),
                )
            }
            return try {
                file.parent?.let(Files::createDirectories)
                val connection = DriverManager.getConnection("jdbc:sqlite:$file")
                ownerOnly(file)
                connection.createStatement().use { statement ->
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS meta (name TEXT PRIMARY KEY, value BLOB NOT NULL)")
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS credentials (name TEXT PRIMARY KEY, nonce BLOB NOT NULL, " +
                            "ciphertext BLOB NOT NULL, updated_at TEXT NOT NULL)",
                    )
                }
                unlock(connection, storeKey).also { if (it is Outcome.Err) connection.close() }
            } catch (e: SQLException) {
                Outcome.Err(SellerFlowError.Config("Cannot open the SellerFlow database at $file: ${e.message}"))
            }
        }

        /** The database is readable by its owner only (where the file system has POSIX permissions). */
        private fun ownerOnly(file: Path) {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
        }

        private fun unlock(
            connection: Connection,
            storeKey: String,
        ): Outcome<SqliteCredentialStore> {
            val salt = meta(connection, "salt") ?: ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val key = derive(storeKey, salt)
            val check = meta(connection, "check")
            return when {
                check == null -> {
                    setMeta(connection, "salt", salt)
                    val (nonce, ciphertext) = encrypt(key, CHECK_NAME, CHECK_VALUE)
                    setMeta(connection, "check", nonce + ciphertext)
                    Outcome.Ok(SqliteCredentialStore(connection, key))
                }
                decrypt(key, CHECK_NAME, check.copyOfRange(0, NONCE_BYTES), check.copyOfRange(NONCE_BYTES, check.size)) ==
                    CHECK_VALUE -> Outcome.Ok(SqliteCredentialStore(connection, key))
                else ->
                    Outcome.Err(
                        SellerFlowError.Config(
                            "SELLERFLOW_STORE_KEY does not open the existing database. Use the key it was created with, " +
                                "or delete the database and log in again.",
                        ),
                    )
            }
        }

        private fun meta(
            connection: Connection,
            name: String,
        ): ByteArray? =
            connection.prepareStatement("SELECT value FROM meta WHERE name = ?").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { if (it.next()) it.getBytes(1) else null }
            }

        private fun setMeta(
            connection: Connection,
            name: String,
            value: ByteArray,
        ) {
            connection.prepareStatement("INSERT OR REPLACE INTO meta (name, value) VALUES (?, ?)").use { statement ->
                statement.setString(1, name)
                statement.setBytes(2, value)
                statement.executeUpdate()
            }
        }

        private fun derive(
            storeKey: String,
            salt: ByteArray,
        ): SecretKey {
            val spec = PBEKeySpec(storeKey.toCharArray(), salt, ITERATIONS, KEY_BITS)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            return SecretKeySpec(bytes, "AES")
        }

        private fun encrypt(
            key: SecretKey,
            name: String,
            value: String,
        ): Pair<ByteArray, ByteArray> {
            val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(name.toByteArray())
            return nonce to cipher.doFinal(value.toByteArray())
        }

        /** The plain value, or null when the key or the name does not match (GCM authentication fails). */
        private fun decrypt(
            key: SecretKey,
            name: String,
            nonce: ByteArray,
            ciphertext: ByteArray,
        ): String? =
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(name.toByteArray())
                String(cipher.doFinal(ciphertext))
            } catch (e: GeneralSecurityException) {
                null.also { e.javaClass }
            }
    }
}
