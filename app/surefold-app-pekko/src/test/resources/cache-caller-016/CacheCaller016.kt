package compatibility.cache016

import dev.surefold.cache.Cache
import dev.surefold.cache.Invalidation
import dev.surefold.fail.right
import kotlin.time.Duration.Companion.minutes

object CacheCaller016 {
    @JvmStatic
    fun run(): Boolean {
        Cache<String, Nothing, String>(ttl = { 1.minutes }) { it.right() }.use { cache ->
            check(cache.get("one") == "one".right())
            check(cache.stats().loads == 1L)
            cache.refresh("one")
            cache.invalidate(Invalidation.Key("one"))
            cache.invalidate(Invalidation.All)
            cache.stats()
        }
        val closeable: AutoCloseable = Cache<String, Nothing, String>({ 1.minutes }) { it.right() }
        closeable.close()
        return true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(run())
        println("CacheCaller016 passed on pinned published 0.16.0")
    }
}
