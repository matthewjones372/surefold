package dev.surefold.actor.remote.avro

import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecordBase

/** Version 1 of an order placed, written out as Avro's compiler would, so that the build needs no compiler. */
class Placed() : SpecificRecordBase() {
    var order: String = ""
    var pence: Long = 0

    constructor(order: String, pence: Long) : this() {
        this.order = order
        this.pence = pence
    }

    override fun getSchema(): Schema = `SCHEMA$`

    override fun get(field: Int): Any = when (field) {
        0 -> order
        1 -> pence
        else -> error("Placed has no field $field")
    }

    override fun put(field: Int, value: Any?) = when (field) {
        0 -> order = value.toString()
        1 -> pence = value as Long
        else -> error("Placed has no field $field")
    }

    override fun equals(other: Any?) = other is Placed && other.order == order && other.pence == pence

    override fun hashCode() = order.hashCode() * 31 + pence.hashCode()

    companion object {
        // Avro's SpecificData finds a record's schema by this static field's name, as generated classes have it.
        @Suppress("ObjectPropertyNaming")
        @JvmField
        val `SCHEMA$`: Schema = Schema.Parser().parse(
            """
            {"type": "record", "name": "Placed", "namespace": "dev.surefold.actor.remote.avro",
             "fields": [{"name": "order", "type": "string"}, {"name": "pence", "type": "long"}]}
            """,
        )

        /** Version 2, as a node that has moved on would write it: a currency, with a default for readers of 1. */
        val VERSION_2: Schema = Schema.Parser().parse(
            """
            {"type": "record", "name": "Placed", "namespace": "dev.surefold.actor.remote.avro",
             "fields": [{"name": "order", "type": "string"}, {"name": "pence", "type": "long"},
                        {"name": "currency", "type": "string", "default": "GBP"}]}
            """,
        )
    }
}
