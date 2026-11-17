package io.github.anetesa.sellerflow.storage

import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.ShipmentId
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File

class SqliteShipmentLedgerTest :
    FunSpec({
        test("remembers the first shipment of an order across openings") {
            val file = File(tempdir(), "sellerflow.db").toPath()
            SqliteShipmentLedger.open(file).use {
                it.shipmentFor(OrderId("o-1")) shouldBe null
                it.record(OrderId("o-1"), ShipmentId("100"))
                it.record(OrderId("o-1"), ShipmentId("200"))
            }
            SqliteShipmentLedger.open(file).use { it.shipmentFor(OrderId("o-1")) shouldBe ShipmentId("100") }
        }
    })
