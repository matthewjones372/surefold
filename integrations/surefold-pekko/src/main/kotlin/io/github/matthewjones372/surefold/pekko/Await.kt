package io.github.matthewjones372.surefold.pekko

import dev.surefold.await
import scala.concurrent.Future
import scala.jdk.javaapi.FutureConverters

/** `surefold`'s `CompletionStage.await()` for the Scala future half of Pekko's API answers with. */
fun <T> Future<T>.await(): T = FutureConverters.asJava(this).await()
