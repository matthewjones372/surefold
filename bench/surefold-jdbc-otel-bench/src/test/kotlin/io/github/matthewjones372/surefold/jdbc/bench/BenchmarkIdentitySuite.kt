package io.github.matthewjones372.surefold.jdbc.bench

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import javax.sql.DataSource

object BenchmarkIdentitySuite : Suite() {
    init {
        test("the qualified database identity is accepted and probe resources are closed") {
            val closed = Shared(emptyList<String>())
            identitySource("qualified", "123", closed).verifyDatabase("qualified", "123")
            closed.peek() shouldBe listOf("rows", "statement", "connection")
        }
        test("a different database is refused before the workload") {
            val closed = Shared(emptyList<String>())
            shouldThrow<IllegalStateException>({
                identitySource("different", "123", closed).verifyDatabase("qualified", "123")
            })
            closed.peek() shouldBe listOf("rows", "statement", "connection")
        }
        test("a different PostgreSQL cluster with the same database name is refused") {
            val closed = Shared(emptyList<String>())
            shouldThrow<IllegalStateException>({
                identitySource("qualified", "456", closed).verifyDatabase("qualified", "123")
            })
            closed.peek() shouldBe listOf("rows", "statement", "connection")
        }
    }
}

private fun identitySource(database: String, system: String, closed: Shared<List<String>>): DataSource {
    val rows = identityFake(ResultSet::class.java) { method, args ->
        when (method.name) {
            "next" -> true
            "getString" -> if (args!![0] == 1) database else system
            "close" -> { closed.update { it + "rows" }; null }
            else -> error("unexpected result call")
        }
    }
    val statement = identityFake(Statement::class.java) { method, _ ->
        when (method.name) {
            "executeQuery" -> rows
            "close" -> { closed.update { it + "statement" }; null }
            else -> error("unexpected statement call")
        }
    }
    val connection = identityFake(Connection::class.java) { method, _ ->
        when (method.name) {
            "createStatement" -> statement
            "close" -> { closed.update { it + "connection" }; null }
            else -> error("unexpected connection call")
        }
    }
    return identityFake(DataSource::class.java) { method, _ ->
        when (method.name) {
            "getConnection" -> connection
            else -> error("unexpected source call")
        }
    }
}

private fun <A> identityFake(type: Class<A>, invoke: (Method, Array<out Any?>?) -> Any?): A = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invoke(method, args) },
)
