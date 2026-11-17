package io.github.anetesa.sellerflow.storage

import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.store.InvoiceLedger
import io.github.anetesa.sellerflow.core.store.IssuedInvoice
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.YearMonth

/** Issued invoices and the per-month counter in the SellerFlow database; recording is one transaction. */
class SqliteInvoiceLedger private constructor(
    private val connection: Connection,
) : InvoiceLedger,
    AutoCloseable {
    @Synchronized
    override fun invoiceFor(order: OrderId): IssuedInvoice? =
        connection.prepareStatement("SELECT number, ksef_number, issued_on FROM invoices WHERE order_id = ?").use { statement ->
            statement.setString(1, order.value)
            statement.executeQuery().use { row ->
                if (row.next()) {
                    IssuedInvoice(order, row.getString("number"), row.getString("ksef_number"), LocalDate.parse(row.getString("issued_on")))
                } else {
                    null
                }
            }
        }

    @Synchronized
    override fun nextSequence(month: YearMonth): Int =
        connection.prepareStatement("SELECT last FROM invoice_counters WHERE month = ?").use { statement ->
            statement.setString(1, month.toString())
            statement.executeQuery().use { if (it.next()) it.getInt(1) + 1 else 1 }
        }

    @Synchronized
    override fun record(
        invoice: IssuedInvoice,
        month: YearMonth,
        sequence: Int,
    ): Boolean {
        connection.autoCommit = false
        return try {
            val recorded =
                invoiceFor(invoice.orderId) == null && sequence == nextSequence(month) && insert(invoice) && advance(month, sequence)
            if (recorded) connection.commit() else connection.rollback()
            recorded
        } catch (e: SQLException) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun insert(invoice: IssuedInvoice): Boolean =
        connection
            .prepareStatement("INSERT INTO invoices (order_id, number, ksef_number, issued_on) VALUES (?, ?, ?, ?)")
            .use { statement ->
                listOf(invoice.orderId.value, invoice.number, invoice.ksefNumber, invoice.issuedOn.toString())
                    .forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeUpdate() == 1
            }

    private fun advance(
        month: YearMonth,
        sequence: Int,
    ): Boolean =
        connection
            .prepareStatement(
                "INSERT INTO invoice_counters (month, last) VALUES (?, ?) ON CONFLICT (month) DO UPDATE SET last = excluded.last",
            ).use { statement ->
                statement.setString(1, month.toString())
                statement.setInt(2, sequence)
                statement.executeUpdate() == 1
            }

    override fun close() = connection.close()

    companion object {
        fun open(file: Path): SqliteInvoiceLedger {
            file.parent?.let(Files::createDirectories)
            val connection = DriverManager.getConnection("jdbc:sqlite:$file")
            connection.createStatement().use {
                it.executeUpdate("CREATE TABLE IF NOT EXISTS invoice_counters (month TEXT PRIMARY KEY, last INTEGER NOT NULL)")
                it.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS invoices (order_id TEXT PRIMARY KEY, number TEXT NOT NULL UNIQUE, " +
                        "ksef_number TEXT NOT NULL, issued_on TEXT NOT NULL)",
                )
            }
            return SqliteInvoiceLedger(connection)
        }
    }
}
