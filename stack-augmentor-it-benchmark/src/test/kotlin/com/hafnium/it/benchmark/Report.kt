package com.hafnium.it.benchmark

import java.io.File
import java.util.Locale

/**
 * Writes the HTML report of the benchmark: `Report <results directory> <report file>`. One row per scenario and
 * whether the exception was logged, one column per mode, and the start of a logged trace of each.
 */
fun main(args: Array<String>) {
    val results = File(args[0])
    val report = File(args[1])
    val measured = Mode.entries.associateWith { mode -> read(File(results, "${mode.id}.tsv")) }
    report.parentFile.mkdirs()
    report.writeText(html(measured, results))
    println("Benchmark report: ${report.toURI()}")
}

private data class Row(val scenario: Scenario, val logged: Boolean)

private fun read(file: File): Map<Row, Measurement> = if (!file.exists()) emptyMap() else file.readLines()
    .filter { it.isNotBlank() }
    .associate { line ->
        val f = line.split('\t')
        Row(Scenario.valueOf(f[0]), f[1].toBoolean()) to Measurement(f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toInt())
    }

private fun html(measured: Map<Mode, Map<Row, Measurement>>, results: File): String = buildString {
    val environment = File(results, "environment.txt").takeIf { it.exists() }?.readText().orEmpty()
    append(
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Exception cost benchmark</title>
        <style>
        :root { --fg: #1f2328; --muted: #656d76; --bg: #ffffff; --line: #d0d7de; --head: #f6f8fa; --good: #1a7f37; --bad: #cf222e; }
        @media (prefers-color-scheme: dark) {
          :root { --fg: #e6edf3; --muted: #8d96a0; --bg: #0d1117; --line: #30363d; --head: #161b22; --good: #3fb950; --bad: #f85149; }
        }
        body { font: 15px/1.5 system-ui, sans-serif; color: var(--fg); background: var(--bg); margin: 0 auto; padding: 24px 16px; max-width: 1100px; }
        h1 { font-size: 22px; margin: 0 0 4px; } h2 { font-size: 17px; margin: 32px 0 8px; }
        p { color: var(--muted); margin: 4px 0; }
        .scroll { overflow-x: auto; }
        table { border-collapse: collapse; width: 100%; margin-top: 12px; }
        th, td { border: 1px solid var(--line); padding: 6px 10px; text-align: right; vertical-align: top; }
        th { background: var(--head); font-weight: 600; }
        th:first-child, td:first-child { text-align: left; }
        .ratio { display: block; font-size: 12px; color: var(--muted); }
        .worse { color: var(--bad); } .same { color: var(--good); }
        details { margin: 6px 0; } summary { cursor: pointer; }
        pre { font-size: 12px; background: var(--head); border: 1px solid var(--line); padding: 8px; overflow-x: auto; }
        </style>
        </head>
        <body>
        <h1>Exception cost in a ${Scenario.FRAMES}-frame stack</h1>
        <p>Microseconds per exception: created, caught and, if logged, turned into text with <code>stackTraceToString()</code>.
        Median of the measured batches after a warm-up; each mode runs in its own JVM. ${escape(environment)}</p>
        <p>Bottom: the deepest frame, where the exception is created. Near the bottom: caught 3 frames above it. Near the top:
        caught 3 frames below the outermost frame of the stack, so it leaves almost all of them. Configured frames show
        their receiver id and the depth parameter.</p>
        """.trimIndent()
    )
    append("\n<div class=\"scroll\"><table>\n<tr><th>Scenario</th><th>Logged</th>")
    Mode.entries.forEach { append("<th>").append(escape(it.title)).append("</th>") }
    append("</tr>\n")
    for (scenario in Scenario.entries) {
        for (logged in listOf(true, false)) {
            val row = Row(scenario, logged)
            val plain = measured[Mode.PLAIN]?.get(row)
            append("<tr><td>").append(escape(scenario.title)).append("</td><td>").append(if (logged) "yes" else "no").append("</td>")
            for (mode in Mode.entries) {
                val m = measured[mode]?.get(row)
                append("<td>")
                if (m == null) {
                    append("&mdash;")
                } else {
                    append(format(m.median))
                    if (mode != Mode.PLAIN && plain != null) {
                        val ratio = m.median / plain.median
                        val css = if (ratio < 1.5) "same" else "worse"
                        append("<span class=\"ratio $css\">").append(String.format(Locale.ROOT, "%.1f× no agent", ratio)).append("</span>")
                    }
                    append("<span class=\"ratio\">").append(format(m.min)).append("–").append(format(m.max)).append("</span>")
                }
                append("</td>")
            }
            append("</tr>\n")
        }
    }
    append("</table></div>\n")
    append("<p>The small numbers under each time are its ratio to no agent and the range of the batches. &mdash; means the mode did not run, e.g. live-stack mode without the native library.</p>\n")

    append("<h2>Logged traces</h2>\n<p>The first lines of one logged trace, and its first configured frame.</p>\n")
    for (scenario in Scenario.entries) {
        append("<details><summary>").append(escape(scenario.title)).append("</summary>\n")
        for (mode in Mode.entries) {
            val log = File(results, "${mode.id}-${scenario.name}.log").takeIf { it.exists() }?.readLines() ?: continue
            val head = log.take(6)
            val configured = log.withIndex().firstOrNull { it.value.contains("Configured{") }
            val lines = if (configured == null || configured.index < head.size) head
            else head + "\t... ${configured.index - head.size} more lines ..." + configured.value
            append("<p>").append(escape(mode.title)).append("</p><pre>").append(escape(lines.joinToString("\n"))).append("</pre>\n")
        }
        append("</details>\n")
    }
    append("</body>\n</html>\n")
}

private fun format(micros: Double): String = when {
    micros >= 100 -> String.format(Locale.ROOT, "%,.0f µs", micros)
    micros >= 10 -> String.format(Locale.ROOT, "%.1f µs", micros)
    else -> String.format(Locale.ROOT, "%.2f µs", micros)
}

private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
