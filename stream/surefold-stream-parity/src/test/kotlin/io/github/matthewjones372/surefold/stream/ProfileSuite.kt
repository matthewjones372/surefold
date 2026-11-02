package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.TimeUnit

/** A measured run's profile, drawn onto its diagram: the stage the time went to is the one marked hot. */
object ProfileSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-profile-test")

    private const val SETTLE_SECONDS = 20L

    private fun <R : Any> Run<*, R>.profiledOn(backend: StreamBackend): Profile {
        val profiler = Profiler()
        measured(Measured("profiled", profiler, sampleEvery = 1)).run(backend)
            .toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
        return profiler.profile()
    }

    private fun Profile.hottest(): Int = stages.keys.filter { share(it) != null }.maxBy { share(it)!! }

    private fun String.lineOf(stage: String): String = lines().single { it.contains("[\"$stage") }

    init {
        test("the mapPar baseline's diagram marks mapPar hottest") { system ->
            val parallel = Stream.from(1..200)
                .map { it + 1 }
                .mapPar(8) { n ->
                    Thread.sleep(1)
                    n * 3L
                }
                .runFold(0L) { total, n -> total + n }

            val profile = parallel.profiledOn(PekkoStreams(system))
            val diagram = parallel.render(Layout.Mermaid, profile = profile)

            withClue(diagram) {
                profile.hottest() shouldBe 2
                expect(diagram.lineOf("mapPar(8)").endsWith(":::hot"))
                expect(diagram.lineOf("map ·").endsWith(":::cool"))
                diagram.lineOf("mapPar(8)") shouldContain "200 out"
            }
        }

        test("a run on forks is profiled the same way, and its text rendering says where the time went") { _ ->
            val chain = Stream.from(1..50)
                .map { it + 1 }
                .map { n ->
                    Thread.sleep(1)
                    n
                }
                .filter { it % 2 == 0 }
                .runCollect()

            val profile = chain.profiledOn(Forks())
            val text = chain.render(profile = profile)

            withClue(text) {
                profile.hottest() shouldBe 2
                text.lines()[2] shouldContain Regex("""map +ProfileSuite\.kt:\d+ +\d+% · """)
                profile.stages.getValue(3).elements shouldBe 25
            }
        }

        test("a profile rendered against an edited run is refused at the first stage that moved") { _ ->
            val written = Stream.from(1..10).map { it + 1 }.filter { it % 2 == 0 }.runCollect()
            val edited = Stream.from(1..10).filter { it % 2 == 0 }.map { it + 1 }.runCollect()
            val profile = written.profiledOn(Forks())

            expect(written.render(profile = profile).isNotEmpty())
            val moved = Regex(
                """step 1 is map at ProfileSuite\.kt:\d+ in the profile, filter at ProfileSuite\.kt:\d+ in this run""",
            )
            shouldThrow<IllegalArgumentException>({ edited.render(profile = profile) }) { thrown ->
                expect(moved.matches(thrown.message.orEmpty()))
            }
        }

        test("two pipelines measured by one profiler get a profile each, and asking for one profile names both") { _ ->
            val profiler = Profiler()
            val numbers = Stream.from(1..10).map { it + 1 }.runCollect()
            val words = Stream.from(listOf("a", "b", "c")).filter { it != "b" }.runCollect()
            numbers.measured(Measured("ingest", profiler)).run(Forks())
                .toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
            words.measured(Measured("export", profiler)).run(Forks())
                .toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

            val ingest = profiler.profile("ingest").stages.getValue(1)
            val export = profiler.profile("export").stages.getValue(1)
            shouldThrow<IllegalStateException>({ profiler.profile() }) { thrown ->
                thrown.message shouldBe "this profiler saw export and ingest; say which"
            }

            ingest.stage shouldBe "map"
            ingest.elements shouldBe 10
            export.stage shouldBe "filter"
            export.elements shouldBe 2
            expect(ingest.at.orEmpty().startsWith("ProfileSuite.kt:"))
        }
    }
}
