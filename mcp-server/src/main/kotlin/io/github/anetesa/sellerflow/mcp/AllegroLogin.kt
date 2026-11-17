package io.github.anetesa.sellerflow.mcp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import io.github.anetesa.sellerflow.allegro.AllegroAuth
import io.github.anetesa.sellerflow.allegro.AllegroHttp
import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.ConfigLoader
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * `sellerflow allegro-login`: logs the seller in with the device flow (unless a saved login still works), saves the
 * login in the encrypted store and lists the orders waiting for shipment.
 */
internal class AllegroLogin : CliktCommand(name = "allegro-login") {
    override fun help(context: Context) = "Log in to Allegro (device flow) and list the orders waiting for shipment."

    override fun run() {
        val result =
            HttpClient(CIO).use { client ->
                runBlocking {
                    ConfigLoader().load().flatMap { config ->
                        val wiring = Wiring(config, client)
                        wiring.allegroHttp().flatMap { http -> login(wiring, http) }
                    }
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

    private suspend fun login(
        wiring: Wiring,
        http: AllegroHttp,
    ): Outcome<String> {
        val auth = AllegroAuth(http)
        val session = wiring.allegroSession(http)
        val loggedIn: Outcome<Unit> =
            if (session.accessToken() is Outcome.Ok) {
                echo("Using the saved Allegro login.")
                Outcome.Ok(Unit)
            } else {
                auth
                    .startDeviceFlow()
                    .flatMap { code ->
                        echo("Open ${code.verificationUriComplete} and approve SellerFlow (code ${code.userCode}). Waiting…")
                        auth.awaitAuthorization(code)
                    }.map { tokens ->
                        session.save(tokens)
                        echo("Logged in; the access token is valid until ${tokens.expiresAt}.")
                    }
            }
        return loggedIn
            .flatMap { AllegroOrders(http) { session.accessToken() }.list() }
            .map { orders ->
                buildString {
                    append("Orders waiting for shipment: ${orders.size}")
                    orders.forEach { append("\n  ${it.id}  ${it.total}  ${it.delivery.methodName}  ${it.buyer.login}") }
                }
            }
    }
}
