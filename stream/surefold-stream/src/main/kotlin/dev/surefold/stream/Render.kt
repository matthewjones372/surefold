package dev.surefold.stream

import java.util.Locale

/** How [render] lays a pipeline out: [Text] for a log line or a test failure, [Mermaid] for a document. */
sealed interface Layout {
    data object Text : Layout

    data object Mermaid : Layout
}

/**
 * The pipeline as a reader would draw it, in the order data moves: each operator by the name it was written with, its
 * plain arguments, and the line that wrote it. [optimised] shows what a backend compiles instead: after `collapsed` and
 * `fused`.
 */
fun Stream<*, *>.render(layout: Layout = Layout.Text, optimised: Boolean = false): String =
    rendered(node.maybeOptimised(optimised), end = null, layout, profile = null)

fun Pipe<*, *, *>.render(layout: Layout = Layout.Text, optimised: Boolean = false): String =
    rendered(node.maybeOptimised(optimised), end = null, layout, profile = null)

/**
 * As the stream's, with the end the run completes through as its last step, and [profile]'s numbers beside each stage:
 * its share of the busy time, its `busy` and `waiting` per element, and what it emitted. In Mermaid a stage is `hot`,
 * `warm` or `cool` by that share. A profile numbers the stages of the run as it was written, so it goes with the
 * described rendering and not the optimised one. A profile of another run, or of this one before an edit, is refused at
 * the first stage that differs, rather than drawn beside the wrong stages.
 */
fun Run<*, *>.render(layout: Layout = Layout.Text, optimised: Boolean = false, profile: Profile? = null): String {
    require(!(optimised && profile != null)) { "a profile numbers the stages as written; render it without optimised" }
    profile?.let { node.dataOrder().matches(it) }
    return rendered(node.maybeOptimised(optimised), end, layout, profile)
}

/**
 * Every stage [profile] names is the one at its step here, operator and site alike (spec 0148). A stage built by hand,
 * with no operator, is taken as it is.
 */
private fun List<Node>.matches(profile: Profile) {
    profile.stages.toSortedMap().forEach { (step, measured) ->
        val operator = measured.stage ?: return@forEach
        val here = getOrNull(step)
        require(here != null && here.operator == operator && here.site == measured.at) {
            val ours = here?.let { "${named(it.operator, it.site)} in this run" } ?: "and this run has no step $step"
            "step $step is ${named(operator, measured.at)} in the profile, $ours"
        }
    }
}

private fun named(operator: String, at: String?): String = if (at == null) operator else "$operator at $at"

private fun Node.maybeOptimised(optimised: Boolean): Node = if (optimised) optimised() else this

/** What a profile says about one stage, ready to print, and the class Mermaid colours it with. */
private class Numbers(val text: String, val heat: String?)

private fun rendered(node: Node, end: End?, layout: Layout, profile: Profile?): String {
    val order = node.dataOrder()
    val numbers = { of: Node -> profile?.numbers(order.indexOfFirst { it === of }) }
    return when (layout) {
        Layout.Text -> text(node, end, numbers)
        Layout.Mermaid -> mermaid(order, node, end, numbers)
    }
}

private fun Profile.numbers(step: Int): Numbers? =
    stages[step]?.let { stage ->
        // Coloured by the percentage printed, so a stage labelled 20% is never drawn below the 20% line.
        val percent = share(step)?.let { Math.round(it * PERCENT).toInt() }
        val parts =
            listOfNotNull(
                percent?.let { "$it%" },
                stage.busyMillis?.let { "${duration(it)} busy" },
                stage.waitingMillis?.let { "${duration(it)} waiting" },
                "${stage.elements} out",
            )
        Numbers(parts.joinToString(" · "), percent?.let(::heat))
    }

/** A duration in the unit it reads best in: a stage body is often nanoseconds, and a wait often milliseconds. */
private fun duration(millis: Double): String =
    when {
        millis >= 1.0 -> "%.1f ms".format(Locale.ROOT, millis)
        millis >= MICROS -> "%.1f µs".format(Locale.ROOT, millis / MICROS)
        else -> "%.0f ns".format(Locale.ROOT, millis / NANOS)
    }

private const val MICROS = 0.001
private const val NANOS = 0.000_001

private const val PERCENT = 100
private const val HOT = 50
private const val WARM = 20

private fun heat(percent: Int): String =
    when {
        percent >= HOT -> "hot"
        percent >= WARM -> "warm"
        else -> "cool"
    }

/** One line of the text layout: the tree drawing and label, the caller's line, and a profile's numbers. */
private class Row(val label: String, val site: String?, val numbers: String? = null)

private fun text(node: Node, end: End?, numbers: (Node) -> Numbers?): String {
    val rows = node.rows(numbers) + listOfNotNull(end?.row())
    val width = rows.maxOf { it.label.length } + 2
    val siteWidth = rows.maxOf { it.site?.length ?: 0 } + 2
    return rows.joinToString("\n") { row ->
        when {
            row.numbers != null -> row.label.padEnd(width) + row.site.orEmpty().padEnd(siteWidth) + row.numbers
            row.site != null -> row.label.padEnd(width) + row.site
            else -> row.label
        }
    }
}

/**
 * A straight run of operators is a flat list, source first. An operator that reads more than one stream heads a branch
 * for each, drawn beneath it, in the order its elements come from them.
 */
private fun Node.rows(numbers: (Node) -> Numbers?): List<Row> {
    val inputs = children()
    val here = Row(label(), site, numbers(this)?.text)
    return if (inputs.size < 2) {
        inputs.flatMap { it.rows(numbers) } + here
    } else {
        listOf(Row("${here.label} of", here.site, here.numbers)) +
            inputs.flatMapIndexed { index, input -> input.rows(numbers).branch(last = index == inputs.lastIndex) }
    }
}

private fun List<Row>.branch(last: Boolean): List<Row> = mapIndexed { index, row ->
    val lead =
        when {
            index == 0 && last -> "└ "
            index == 0 -> "├ "
            last -> "  "
            else -> "│ "
        }
    Row(lead + row.label, row.site, row.numbers)
}

private fun End.row(): Row =
    when (this) {
        End.Collect -> Row("runCollect", null)
        is End.Fold -> Row("runFold", at)
        is End.Native -> Row(builder, at)
    }

private fun mermaid(nodes: List<Node>, node: Node, end: End?, numbers: (Node) -> Numbers?): String {
    val id = { of: Node -> "n${nodes.indexOfFirst { it === of }}" }
    val declared = nodes.map { each ->
        val profiled = numbers(each)
        val label = quoted(each.label(), each.site) + profiled?.let { "<br/>" + quoted(it.text, null) }.orEmpty()
        "    ${id(each)}[\"$label\"]" + profiled?.heat?.let { ":::$it" }.orEmpty()
    }
    val edges = nodes.flatMap { to -> to.children().map { from -> "    ${id(from)} --> ${id(to)}" } }
    // `end` is a Mermaid keyword, so the run's last step is `run`.
    val ending =
        end?.row()
            ?.let { row ->
                listOf("    run[\"${quoted(row.label, row.site)}\"]", "    ${id(node)} --> run")
            }
            .orEmpty()
    val classes = if (nodes.any { numbers(it)?.heat != null }) HEAT_CLASSES else emptyList()
    return (listOf("flowchart TD") + declared + edges + ending + classes).joinToString("\n")
}

private val HEAT_CLASSES =
    listOf(
        "    classDef hot fill:#f4a6a6,stroke:#b42318",
        "    classDef warm fill:#fbd38d,stroke:#b7791f",
        "    classDef cool fill:#c6f6d5,stroke:#2f855a",
    )

/** A Mermaid label in double quotes: a quote inside it is Mermaid's own entity, not the end of the label. */
private fun quoted(label: String, site: String?): String =
    (label + site?.let { " · $it" }.orEmpty()).replace("\"", "#quot;")

/** The operator and the arguments a reader needs to tell one of it from another. A lambda says nothing. */
private fun Node.label(): String {
    val arguments =
        when (this) {
            is Node.Take -> "$n"

            is Node.Drop -> "$n"

            is Node.Grouped -> "$n"

            is Node.Sliding -> "$n, $step"

            is Node.Buffer -> "$size"

            is Node.GroupedWithin -> "$n, $within"

            is Node.Tick -> "$every"

            is Node.MapPar -> if (ordered) "$parallelism" else "$parallelism, unordered"

            is Node.MapAsync -> if (ordered) "$parallelism" else "$parallelism, unordered"

            is Node.Interleave -> "$segmentSize"

            is Node.FlatMap -> breadth?.toString()

            is Node.Native,
            is Node.Stage,
            is Node.Elements,
            is Node.Single,
            is Node.FromStage,
            is Node.Fail,
            is Node.Blocking,
            Node.Empty,
            Node.Hole,
            is Node.Merge,
            is Node.ZipWith,
            is Node.Prepend,
            is Node.Concat,
            is Node.RestartOnDefect,
            is Node.Map,
            is Node.MapOrFail,
            is Node.Filter,
            is Node.FilterNot,
            is Node.TakeWhile,
            is Node.DropWhile,
            is Node.Scan,
            is Node.StatefulMap,
            is Node.MapConcat,
            is Node.Conflate,
            is Node.Either,
            is Node.Absolve,
            is Node.CatchAll,
            is Node.MapError,
            is Node.OrFailIfEmpty,
            is Node.Fused,
            is Node.Probed -> null
        }
    return operator + arguments?.let { "($it)" }.orEmpty()
}
