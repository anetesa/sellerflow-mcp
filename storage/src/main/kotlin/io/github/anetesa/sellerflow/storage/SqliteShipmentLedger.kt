package io.github.anetesa.sellerflow.storage

import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.ShipmentId
import io.github.anetesa.sellerflow.core.store.ShipmentLedger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Order → shipment mapping in the SellerFlow database. Not secret, so not encrypted; the first shipment recorded for
 * an order stays (the primary key refuses a second one).
 */
class SqliteShipmentLedger private constructor(
    private val connection: Connection,
) : ShipmentLedger,
    AutoCloseable {
    @Synchronized
    override fun shipmentFor(order: OrderId): ShipmentId? =
        connection.prepareStatement("SELECT shipment_id FROM order_shipments WHERE order_id = ?").use { statement ->
            statement.setString(1, order.value)
            statement.executeQuery().use { if (it.next()) ShipmentId(it.getString(1)) else null }
        }

    @Synchronized
    override fun record(
        order: OrderId,
        shipment: ShipmentId,
    ) {
        connection
            .prepareStatement(
                "INSERT OR IGNORE INTO order_shipments (order_id, shipment_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
            ).use { statement ->
                statement.setString(1, order.value)
                statement.setString(2, shipment.value)
                statement.executeUpdate()
            }
    }

    override fun close() = connection.close()

    companion object {
        fun open(file: Path): SqliteShipmentLedger {
            file.parent?.let(Files::createDirectories)
            val connection = DriverManager.getConnection("jdbc:sqlite:$file")
            connection.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS order_shipments " +
                        "(order_id TEXT PRIMARY KEY, shipment_id TEXT NOT NULL, created_at TEXT NOT NULL)",
                )
            }
            return SqliteShipmentLedger(connection)
        }
    }
}
