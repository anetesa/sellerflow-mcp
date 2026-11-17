package io.github.anetesa.sellerflow.mcp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import io.github.anetesa.sellerflow.allegro.AllegroAuth
import io.github.anetesa.sellerflow.allegro.AllegroHttp
import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.ConfigLoader
import io.github.anetesa.sellerflow.core.config.Secrets
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * `sellerflow allegro-login`: logs the seller in with the device flow and lists the orders waiting for shipment.
 * The tokens stay in memory for now; keeping them between runs comes with the encrypted storage.
 */
internal class AllegroLogin : CliktCommand(name = "allegro-login") {
    override fun help(context: Context) = "Log in to Allegro (device flow) and list the orders waiting for shipment."

    override fun run() {
        val result =
            HttpClient(CIO).use { client ->
                runBlocking {
                    ConfigLoader()
                        .load()
                        .flatMap { config ->
                            AllegroHttp.create(
                                client = client,
                                endpoints = config.endpoints,
                                clientId = config.allegro.clientId,
                                clientSecret = Secrets().allegroClientSecret(),
                                userAgent = config.allegro.userAgent,
                            )
                        }.flatMap { http -> login(http) }
                }
            }
        when (result) {
            is Outcome.Ok -> echo(result.value)
            is Outcome.Err -> {
                echo(result.error.message, err = true)
                exitProcess(1)
            }
        }
    }

    private suspend fun login(http: AllegroHttp): Outcome<String> {
        val auth = AllegroAuth(http)
        return auth
            .startDeviceFlow()
            .flatMap { code ->
                echo("Open ${code.verificationUriComplete} and approve SellerFlow (code ${code.userCode}). Waiting…")
                auth.awaitAuthorization(code)
            }.flatMap { tokens ->
                echo("Logged in; the access token is valid until ${tokens.expiresAt}.")
                AllegroOrders(http) { Outcome.Ok(tokens.accessToken) }.list()
            }.map { orders ->
                buildString {
                    append("Orders waiting for shipment: ${orders.size}")
                    orders.forEach { append("\n  ${it.id}  ${it.total}  ${it.delivery.methodName}  ${it.buyer.login}") }
                }
            }
    }
}
