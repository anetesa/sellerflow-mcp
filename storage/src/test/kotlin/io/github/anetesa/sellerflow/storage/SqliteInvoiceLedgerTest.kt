package io.github.anetesa.sellerflow.storage

import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.store.IssuedInvoice
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

private val NOVEMBER = YearMonth.of(2026, 11)

private fun invoice(
    order: String,
    n: Int,
) = IssuedInvoice(OrderId(order), "FV/$n/11/2026", "5252674798-20261117-$n", LocalDate.parse("2026-11-17"))

class SqliteInvoiceLedgerTest :
    FunSpec({
        test("numbers count per month, survive reopening and never repeat for an order") {
            val file = File(tempdir(), "sellerflow.db").toPath()
            SqliteInvoiceLedger.open(file).use { ledger ->
                ledger.nextSequence(NOVEMBER) shouldBe 1
                ledger.record(invoice("o-1", 1), NOVEMBER, 1) shouldBe true
                ledger.nextSequence(NOVEMBER) shouldBe 2
                ledger.nextSequence(YearMonth.of(2026, 12)) shouldBe 1
                ledger.record(invoice("o-1", 2), NOVEMBER, 2) shouldBe false
            }
            SqliteInvoiceLedger.open(file).use { ledger ->
                ledger.invoiceFor(OrderId("o-1"))?.number shouldBe "FV/1/11/2026"
                ledger.nextSequence(NOVEMBER) shouldBe 2
            }
        }

        test("a stale sequence is refused and leaves no trace") {
            SqliteInvoiceLedger.open(File(tempdir(), "sellerflow.db").toPath()).use { ledger ->
                ledger.record(invoice("o-1", 1), NOVEMBER, 1) shouldBe true
                ledger.record(invoice("o-2", 1), NOVEMBER, 1) shouldBe false
                ledger.invoiceFor(OrderId("o-2")) shouldBe null
                ledger.nextSequence(NOVEMBER) shouldBe 2
            }
        }
    })
