package io.github.anetesa.sellerflow.mcp.tools

import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.config.SellerSettings
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.github.anetesa.sellerflow.core.model.Order
import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.store.InvoiceLedger
import io.github.anetesa.sellerflow.core.store.IssuedInvoice
import io.github.anetesa.sellerflow.invoice.Fa3Document
import io.github.anetesa.sellerflow.invoice.Fa3InvoiceBuilder
import io.github.anetesa.sellerflow.ksef.KsefInvoices
import io.github.anetesa.sellerflow.ksef.KsefSession
import io.github.anetesa.sellerflow.mcp.confirm.ConfirmTokens
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Issues the FA(3) invoice of an Allegro order in KSeF: a preview with the number it will get and a confirm_token
 * first, the real invoice only with that token, never a second invoice for one order, and numbers taken only for
 * invoices KSeF accepted.
 */
class KsefTools(
    private val seller: SellerSettings,
    private val environment: Environment,
    private val orders: AllegroOrders?,
    private val session: KsefSession,
    private val invoices: KsefInvoices,
    private val ledger: InvoiceLedger,
    private val tokens: ConfirmTokens,
    private val upoDir: Path,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Warsaw")),
) {
    private val builder = Fa3InvoiceBuilder(seller.profile, seller.vatRate)

    internal suspend fun issueInvoice(
        orderId: OrderId,
        dryRun: Boolean,
        confirmToken: String?,
    ): String =
        plan(orderId)
            .flatMap { draft ->
                val what = listOf(draft.order.id, draft.document.number, draft.document.gross, draft.order.invoiceRequest).joinToString("|")
                if (dryRun) Outcome.Ok(preview(draft, tokens.issue(ACTION, what))) else issue(draft, confirmToken, what)
            }.text()

    private class Draft(
        val order: Order,
        val document: Fa3Document,
        val month: YearMonth,
        val sequence: Int,
        val issueDate: LocalDate,
    )

    private suspend fun plan(orderId: OrderId): Outcome<Draft> {
        val existing = ledger.invoiceFor(orderId)
        return when {
            existing != null ->
                Outcome.Err(
                    SellerFlowError.AlreadyDone(
                        "Order $orderId already has invoice ${existing.number}, KSeF number ${existing.ksefNumber}; " +
                            "SellerFlow never issues a second one.",
                    ),
                )
            orders == null -> Outcome.Err(SellerFlowError.Config("Allegro is not set up, so SellerFlow cannot read order $orderId."))
            else ->
                orders.get(orderId).flatMap { order ->
                    val today = LocalDate.now(clock)
                    val month = YearMonth.from(today)
                    val sequence = ledger.nextSequence(month)
                    val number = "${seller.invoicePrefix}/$sequence/${"%02d".format(month.monthValue)}/${month.year}"
                    builder.build(order, number, today, clock.instant()).map { Draft(order, it, month, sequence, today) }
                }
        }
    }

    private fun preview(
        draft: Draft,
        token: String,
    ): String =
        buildString {
            val request = draft.order.invoiceRequest!!
            appendLine("PREVIEW, nothing was sent to KSeF yet. FA(3) invoice for Allegro order ${draft.order.id}:")
            appendLine(
                when (environment) {
                    Environment.SANDBOX -> "  Environment: KSeF TEST (no legal effect)"
                    Environment.PROD -> "  Environment: KSeF PRODUCTION — once sent, the invoice is legally issued and cannot be deleted"
                },
            )
            appendLine("  Number: ${draft.document.number}, issued ${draft.issueDate}")
            appendLine("  Seller: ${seller.profile.name}, NIP ${seller.profile.nip}")
            appendLine("  Buyer: ${request.name}, " + (request.nip?.let { "NIP $it" } ?: "no NIP (private person)"))
            draft.document.lines.forEach { appendLine("  ${it.quantity} × ${it.name} at ${it.unitGross} gross, VAT ${it.vatRate} %") }
            draft.document.totals.forEach { appendLine("  VAT ${it.rate} %: net ${it.net}, VAT ${it.vat}, gross ${it.gross}") }
            appendLine("  Total: ${draft.document.gross}")
            append("To issue it, call ksef_issue_invoice again with dry_run=false and confirm_token=$token (valid 15 minutes, once).")
        }

    private suspend fun issue(
        draft: Draft,
        confirmToken: String?,
        what: String,
    ): Outcome<String> =
        if (confirmToken.isNullOrBlank()) {
            Outcome.Err(
                SellerFlowError.Invalid("Issuing needs the confirm_token from a preview: call with dry_run=true first and show it."),
            )
        } else {
            tokens
                .redeem(confirmToken, ACTION, what)
                .flatMap { session.accessToken() }
                .flatMap { access -> invoices.issue(access, draft.document.xml) }
                .map { receipt ->
                    val issued = IssuedInvoice(draft.order.id, draft.document.number, receipt.ksefNumber, draft.issueDate)
                    val recorded = ledger.record(issued, draft.month, draft.sequence)
                    val upo = receipt.upoXml?.let { saveUpo(receipt.ksefNumber, it) }
                    buildString {
                        append("Issued invoice ${draft.document.number} for order ${draft.order.id}: KSeF number ${receipt.ksefNumber}.")
                        upo?.let { append(" UPO saved to $it.") }
                        if (!recorded) {
                            append(" WARNING: the local invoice register did not take it (another invoice took the number); ")
                            append("check the numbering.")
                        }
                    }
                }
        }

    private fun saveUpo(
        ksefNumber: String,
        xml: String,
    ): Path {
        Files.createDirectories(upoDir)
        return upoDir.resolve("UPO-$ksefNumber.xml").also { Files.writeString(it, xml) }
    }

    private companion object {
        const val ACTION = "ksef_issue_invoice"
    }
}

private fun Outcome<String>.text(): String =
    when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> error.message
    }
