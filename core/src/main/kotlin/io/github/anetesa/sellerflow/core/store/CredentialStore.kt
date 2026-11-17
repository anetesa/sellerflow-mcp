package io.github.anetesa.sellerflow.core.store

/**
 * Where logins survive between runs: OAuth tokens and the like, kept encrypted at rest. Values are opaque strings;
 * each client decides what it stores under its own [name]s.
 */
interface CredentialStore {
    fun get(name: String): String?

    fun put(
        name: String,
        value: String,
    )

    fun remove(name: String)
}

/** A store that forgets everything when the process ends: tests, and runs without SELLERFLOW_STORE_KEY. */
class InMemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<String, String>()

    @Synchronized
    override fun get(name: String): String? = values[name]

    @Synchronized
    override fun put(
        name: String,
        value: String,
    ) {
        values[name] = value
    }

    @Synchronized
    override fun remove(name: String) {
        values.remove(name)
    }
}
