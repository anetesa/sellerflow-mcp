package io.github.anetesa.sellerflow.mcp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.ConfigLoader
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlin.system.exitProcess

fun main(args: Array<String>) = Cli().subcommands(Serve()).main(args)

private class Cli : CliktCommand(name = "sellerflow") {
    override fun help(context: Context) = "Allegro → InPost → KSeF fulfillment for AI agents, over MCP."

    override fun run() = Unit
}

/** `sellerflow serve`: MCP over stdio, the way Claude Desktop, Claude Code and MCP Inspector start servers. */
private class Serve : CliktCommand(name = "serve") {
    override fun help(context: Context) = "Run the MCP server on stdin/stdout."

    override fun run() {
        val config =
            when (val loaded = ConfigLoader().load()) {
                is Outcome.Ok -> loaded.value
                is Outcome.Err -> {
                    // stdout belongs to the protocol: problems go to stderr
                    System.err.println(loaded.error.message)
                    exitProcess(1)
                }
            }
        val server = SellerFlowServer.create(config)
        val transport = StdioServerTransport(System.`in`.asInput(), System.out.asSink().buffered()) {}
        runBlocking {
            val session = server.createSession(transport)
            val closed = CompletableDeferred<Unit>()
            session.onClose { closed.complete(Unit) }
            closed.await()
        }
    }
}
