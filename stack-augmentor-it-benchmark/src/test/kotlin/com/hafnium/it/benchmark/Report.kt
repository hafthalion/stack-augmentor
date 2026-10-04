package com.hafnium.it.benchmark

import java.io.File
import java.util.Locale

/**
 * Writes the HTML report of the benchmark: `Report <results directory> <report file>`. One row per scenario and
 * whether the exception was logged, one column per mode, and the start of a logged trace of each. Clicking a column
 * header sorts the table by it.
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
        Row(Scenario.valueOf(f[0]), f[1].toBoolean()) to Measurement(f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toInt(), f[6].toInt())
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
        body { font: 15px/1.5 system-ui, sans-serif; color: var(--fg); background: var(--bg); margin: 0 auto; padding: 24px 16px; max-width: 1300px; }
        h1 { font-size: 22px; margin: 0 0 4px; } h2 { font-size: 17px; margin: 32px 0 8px; }
        p { color: var(--muted); margin: 4px 0; }
        .scroll { overflow-x: auto; }
        table { border-collapse: collapse; width: 100%; margin-top: 12px; }
        th, td { border: 1px solid var(--line); padding: 6px 10px; text-align: right; vertical-align: top; white-space: nowrap; }
        th { white-space: normal; }
        th { background: var(--head); font-weight: 600; cursor: pointer; user-select: none; }
        th[aria-sort="ascending"]::after { content: " \25B2"; } th[aria-sort="descending"]::after { content: " \25BC"; }
        th.text, td.text { text-align: left; }
        .ratio { display: block; font-size: 12px; color: var(--muted); white-space: nowrap; }
        .worse { color: var(--bad); } .same { color: var(--good); }
        .trace { border: 0; background: none; color: var(--muted); cursor: pointer; padding: 0 0 0 4px; font: inherit; }
        .trace:hover, .trace:focus { color: var(--fg); }
        #tip { position: fixed; z-index: 10; display: none; max-width: min(900px, calc(100vw - 32px)); max-height: 70vh; overflow: auto;
          background: var(--head); border: 1px solid var(--line); padding: 8px; box-shadow: 0 4px 16px rgba(0, 0, 0, .25); text-align: left; }
        #tip-title { margin: 0 0 4px; font-size: 16px; font-weight: 600; color: var(--fg); white-space: normal; }
        #tip-description { margin: 0 0 8px; font-size: 13px; white-space: normal; }
        #tip-trace { margin: 0; padding: 0; border: 0; white-space: pre; }
        dt { font-weight: 600; margin-top: 8px; } dd { margin: 2px 0 0 0; color: var(--muted); }
        details { margin: 6px 0; } summary { cursor: pointer; }
        pre { font-size: 12px; background: var(--head); border: 1px solid var(--line); padding: 8px; overflow-x: auto; }
        </style>
        </head>
        <body>
        <h1>Exception cost with ${Scenario.FRAMES} stack trace frames</h1>
        <p>Microseconds per exception: created, caught and, if logged, turned into text with <code>stackTraceToString()</code>.
        Median of the measured batches after a warm-up; each mode runs in its own JVM, each scenario on a new thread, so that
        only a few frames are below the benchmark's. ${escape(environment)}</p>
        <p>The stack has ${Scenario.FRAMES} frames, or fewer with a cause chain, so that its exceptions' stack traces hold
        ${Scenario.FRAMES} frames together. The exception is always created at the bottom, in the deepest frame. Near the bottom: caught 3 frames above it.
        Near the top: caught 3 frames below the outermost frame of the stack, so it leaves almost all of them. Configured
        frames show their receiver id and the depth parameter, which counts from 1 at the top of the stack. Click a column header to sort; click &#x2630; to see
        the first and last 10 frames of the logged stack trace, or the whole trace as printStackTrace prints it. With a cause
        chain, the excerpt starts with the deepest exception, and each exception that wrapped it follows as "Rethrown as:" with
        its first 10 frames, the last one also with its last 10.</p>
        """.trimIndent()
    )
    append("\n<div class=\"scroll\"><table id=\"results\">\n<thead><tr><th class=\"text\">Caught</th><th>Configured frames</th>")
    append("<th class=\"text\">Logged</th>")
    Mode.entries.forEach { append("<th>").append(escape(it.title)).append("</th>") }
    append("</tr></thead>\n<tbody>\n")
    for (scenario in Scenario.entries) {
        for (logged in listOf(true, false)) {
            val row = Row(scenario, logged)
            val plain = measured[Mode.PLAIN]?.get(row)
            append("<tr><td class=\"text\">").append(scenario.caught).append("</td>")
            append("<td data-sort=\"${scenario.configuredPercent}\">").append(scenario.configuredPercent).append("%</td>")
            append("<td class=\"text\">").append(if (logged) "yes" else "no").append("</td>")
            for (mode in Mode.entries) {
                val m = measured[mode]?.get(row)
                append("<td data-sort=\"").append(m?.median?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "").append("\">")
                if (m == null) {
                    append("&mdash;")
                } else {
                    append(format(m.median))
                    traceExcerpt(results, mode, scenario)?.let {
                        val title = "${mode.title}: ${scenario.title}, ${if (logged) "logged" else "not logged"}"
                        val description = (if (logged) "" else "The trace of the logged run; this run does not format it. ") +
                            (if (scenario.causes > 0) "The deepest exception comes first, then each exception that wrapped it, " +
                                "with its first $TRACE_FRAMES frames; the last one also with its last $TRACE_FRAMES frames."
                            else "The first and last $TRACE_FRAMES frames of the stack trace.")
                        append("<button type=\"button\" class=\"trace\" aria-label=\"Stack trace\" data-title=\"")
                            .append(attribute(title)).append("\" data-description=\"").append(attribute(description))
                            .append("\" data-trace=\"").append(attribute(it)).append("\" data-log=\"").append(logKey(mode, scenario))
                            .append("\">&#x2630;</button>")
                    }
                    if (mode != Mode.PLAIN && plain != null) {
                        val ratio = m.median / plain.median
                        val css = if (ratio < 1.5) "same" else "worse"
                        append("<span class=\"ratio $css\">").append(String.format(Locale.ROOT, "%.1f× no agent", ratio)).append("</span>")
                    }
                    append("<span class=\"ratio\">").append(format(m.min)).append("–").append(format(m.max)).append("</span>")
                    append("<span class=\"ratio\">").append(String.format(Locale.ROOT, "n = %d × %,d", m.batches, m.batch))
                        .append("</span>")
                }
                append("</td>")
            }
            append("</tr>\n")
        }
    }
    append("</tbody></table></div>\n<div id=\"tip\" role=\"dialog\"><p id=\"tip-title\"></p><p id=\"tip-description\"></p>")
    append("<p><button type=\"button\" id=\"tip-whole\"></button></p><pre id=\"tip-trace\"></pre></div>\n")
    append(logs(results))
    append(SORT_SCRIPT)
    append("<h2>Columns</h2>\n<dl>\n")
    column("Caught", "Where the exception is caught. It is always created at the bottom, in the deepest frame of the " +
        "stack. Near the bottom: 3 frames above that, so it leaves only 3 frames. Near the top: 3 frames below the outermost " +
        "frame, so it leaves almost all of them. With a cause chain, the stack has ${Scenario.CAUSES_PLAIN.frames} frames, " +
        "and every ${Scenario.CAUSES_PLAIN.wrapEvery} frames a frame catches the exception and throws a new one with it as the cause; " +
        "the outermost one is caught near the top. The stack traces of the ${Scenario.CAUSES_PLAIN.causes + 1} exceptions then " +
        "hold ${Scenario.FRAMES} frames together, as a single exception's does. Each exception leaves its " +
        "${Scenario.CAUSES_PLAIN.wrapEvery} frames, and the causes share the frames below with the exceptions that wrap them, " +
        "which printing collapses into \"... N more\".")
    column("Configured frames", "The share of the frames whose method is configured to show ids (receiver id and " +
        "depth parameter): every 4th frame, or none. The others are methods of an unconfigured class.")
    column("Logged", "Yes: after it is caught, the exception is turned into text with stackTraceToString(), which reads the " +
        "whole stack trace, as logging does. No: it is only created, thrown and caught.")
    Mode.entries.forEach { column(it.title, it.description) }
    append("</dl>\n<p>Each time is the median in microseconds per exception, measured in its own JVM after a warm-up. " +
        "The small numbers under it are its ratio to no agent, the range of the measured batches, and the sample size: n = batches × exceptions per batch, not counting the warm-up; &#x2630; shows the " +
        "start of that mode's logged stack trace. &mdash; means the mode did not run, e.g. the live-stack mode without the " +
        "native library.</p>\n")

    append("</body>\n</html>\n")
}

private fun StringBuilder.column(name: String, description: String) {
    append("<dt>").append(escape(name)).append("</dt><dd>").append(escape(description)).append("</dd>\n")
}

private const val TRACE_FRAMES = 10

private fun logKey(mode: Mode, scenario: Scenario): String = "${mode.id}-${scenario.name}"

/**
 * The logged traces, whole, as a JSON object by [logKey], for the popup's "whole trace" view: each is embedded once,
 * not in every button that shows it.
 */
private fun logs(results: File): String = buildString {
    append("<script type=\"application/json\" id=\"logs\">{")
    val entries = Mode.entries.flatMap { mode -> Scenario.entries.map { mode to it } }.mapNotNull { (mode, scenario) ->
        File(results, "${logKey(mode, scenario)}.log").takeIf { it.exists() }?.let { logKey(mode, scenario) to it.readText() }
    }
    entries.forEachIndexed { i, (key, log) ->
        if (i > 0) append(',')
        append(json(key)).append(':').append(json(log))
    }
    append("}</script>\n")
}

/** A JSON string; "<" is escaped too, so that no "</script>" ends the script element early. */
private fun json(text: String): String = buildString {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '<' || c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> append(c)
        }
    }
    append('"')
}

private fun attribute(text: String): String = escape(text).replace("\"", "&quot;")

/** One exception of a printed trace: its first line, and its frames without the shared ones a cause leaves out. */
private class Printed(val header: String, val frames: List<String>, val omitted: Int)

/** Splits what printStackTrace printed into the exception and its causes, outermost first. */
private fun parse(log: List<String>): List<Printed> {
    val starts = log.indices.filter { it == 0 || log[it].startsWith("Caused by: ") }
    return starts.mapIndexed { n, start ->
        val end = starts.getOrElse(n + 1) { log.size }
        val lines = log.subList(start + 1, end)
        val more = lines.lastOrNull()?.let { MORE.matchEntire(it) }
        Printed(
            log[start].removePrefix("Caused by: "),
            (if (more != null) lines.dropLast(1) else lines).map { it.trim() },
            more?.groupValues?.get(1)?.toInt() ?: 0,
        )
    }
}

/**
 * The trace logged in [mode] and [scenario], deepest exception first, then each exception that wrapped it as
 * "Rethrown as:": the first [TRACE_FRAMES] frames of each, from the frame that created it upwards, and of the last one
 * also its last [TRACE_FRAMES] frames, the top of the stack. Null if no trace was logged.
 */
private fun traceExcerpt(results: File, mode: Mode, scenario: Scenario): String? {
    val log = File(results, "${mode.id}-${scenario.name}.log").takeIf { it.exists() }?.readLines() ?: return null
    val printed = parse(log)
    // The whole stacks: a cause's left-out frames are the last ones of the exception it caused.
    val stacks = mutableListOf<List<String>>()
    for (exception in printed) {
        val enclosing = stacks.lastOrNull().orEmpty()
        stacks += exception.frames + enclosing.takeLast(exception.omitted)
    }
    return buildList {
        for (n in printed.indices.reversed()) {
            add((if (n == printed.lastIndex) "" else "Rethrown as: ") + printed[n].header)
            val stack = stacks[n]
            // The outermost exception, printed last, also shows its last frames: the top of the stack.
            val last = if (n == 0) TRACE_FRAMES else 0
            if (stack.size <= TRACE_FRAMES + last) {
                stack.forEach { add("\t" + it) }
            } else {
                stack.take(TRACE_FRAMES).forEach { add("\t" + it) }
                add("\t... ${stack.size - TRACE_FRAMES - last} more frames")
                stack.takeLast(last).forEach { add("\t" + it) }
            }
        }
    }.joinToString("\n")
}

/** The line where a printed cause leaves out the frames it shares with the exception it caused. */
private val MORE = Regex("""\s*\.\.\. (\d+) more""")

private fun format(micros: Double): String = when {
    micros >= 100 -> String.format(Locale.ROOT, "%,.0f µs", micros)
    micros >= 10 -> String.format(Locale.ROOT, "%.1f µs", micros)
    else -> String.format(Locale.ROOT, "%.2f µs", micros)
}

private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Sorts the results table by the clicked column: by `data-sort` where a cell has it, otherwise by its text. */
private val SORT_SCRIPT = """
    <script>
    (() => {
      const table = document.getElementById("results");
      const headers = [...table.tHead.rows[0].cells];
      const key = (row, i) => {
        const cell = row.cells[i];
        if (!cell.hasAttribute("data-sort")) return cell.textContent;
        const value = cell.getAttribute("data-sort");
        return value === "" ? Infinity : Number(value);
      };
      const tip = document.getElementById("tip");
      const logs = JSON.parse(document.getElementById("logs").textContent);
      const whole = document.getElementById("tip-whole");
      let showsWhole = false;
      const fill = (button) => {
        const log = logs[button.dataset.log];
        whole.hidden = log === undefined;
        whole.textContent = showsWhole ? "Show the excerpt" : "Show the whole trace, as printStackTrace prints it";
        document.getElementById("tip-description").textContent = showsWhole
          ? "The whole logged trace, with the causes and the frames below the benchmark."
          : button.dataset.description;
        document.getElementById("tip-trace").textContent = showsWhole && log !== undefined ? log : button.dataset.trace;
      };
      const show = (button) => {
        document.getElementById("tip-title").textContent = button.dataset.title;
        fill(button);
        tip.scrollTop = 0;
        tip.style.display = "block";
        const box = button.getBoundingClientRect();
        const left = Math.max(16, Math.min(box.left, window.innerWidth - tip.offsetWidth - 16));
        const below = box.bottom + 4 + tip.offsetHeight <= window.innerHeight;
        tip.style.left = left + "px";
        tip.style.top = (below ? box.bottom + 4 : Math.max(4, box.top - 4 - tip.offsetHeight)) + "px";
      };
      let shown = null;
      const hide = () => { tip.style.display = "none"; shown = null; showsWhole = false; };
      whole.addEventListener("click", () => { showsWhole = !showsWhole; show(shown); });
      table.querySelectorAll(".trace").forEach(button => button.addEventListener("click", (event) => {
        event.stopPropagation();
        if (shown === button) { hide(); } else { show(button); shown = button; }
      }));
      tip.addEventListener("click", event => event.stopPropagation());
      document.addEventListener("click", hide);
      document.addEventListener("keydown", event => { if (event.key === "Escape") hide(); });
      window.addEventListener("scroll", event => { if (event.target !== tip) hide(); }, true);
      headers.forEach((th, i) => th.addEventListener("click", () => {
        const ascending = th.getAttribute("aria-sort") !== "ascending";
        headers.forEach(h => h.removeAttribute("aria-sort"));
        th.setAttribute("aria-sort", ascending ? "ascending" : "descending");
        const body = table.tBodies[0];
        const rows = [...body.rows].sort((a, b) => {
          const x = key(a, i), y = key(b, i);
          const order = typeof x === "number" ? x - y : x.localeCompare(y);
          return ascending ? order : -order;
        });
        rows.forEach(row => body.appendChild(row));
      }));
    })();
    </script>

""".trimIndent() + "\n"
