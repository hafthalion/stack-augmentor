package com.hafnium.it.report

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Optional

/**
 * Records the calls a test class makes and writes a report of them for review: build/reports/frames/<class>.json with
 * the raw observations and build/reports/frames/<class>.html, with each test under the @DisplayName of its @Nested
 * class, its Kotlin declarations and the frames it caught. Register it on the top-level test class:
 *
 * ```
 * companion object {
 *     @JvmField
 *     @RegisterExtension
 *     val report = FrameReport("Receiver ids by inheritance", basePackage = "com.acme", ownPackage = "com.acme")
 * }
 * ```
 *
 * and call [trace] or [thrown] in the tests, or [record] with an exception or frames obtained otherwise. The tests of
 * one class must not run in parallel. Each report is listed in build/reports/frames/index.html.
 */
class FrameReport(
    private val title: String,
    private val basePackage: String,
    ownPackage: String = basePackage,
    private val entries: ReceiverEntries? = null,
    private val directory: Path = Path.of("build/reports/frames"),
) : BeforeEachCallback, TestWatcher, AfterAllCallback {

    private val declarations = KotlinDeclarations(basePackage, ownPackage, entries)
    private val observations = mutableListOf<Observation>()

    /** Every test in run order, with its category and whether it passed. */
    private val tests = LinkedHashMap<String, Pair<String, String>>()
    private var category = ""
    private var test = ""
    private var testClassName = ""

    override fun beforeEach(context: ExtensionContext) {
        category = context.requiredTestClass.getAnnotation(DisplayName::class.java)?.value ?: context.requiredTestClass.simpleName
        test = context.displayName.removeSuffix("()")
        testClassName = generateSequence(context.requiredTestClass) { it.enclosingClass }.last().name
    }

    override fun testSuccessful(context: ExtensionContext) = result(context, "passed")

    override fun testFailed(context: ExtensionContext, cause: Throwable?) = result(context, "failed")

    override fun testAborted(context: ExtensionContext, cause: Throwable?) = result(context, "aborted")

    override fun testDisabled(context: ExtensionContext, reason: Optional<String>) = result(context, "disabled")

    private fun result(context: ExtensionContext, status: String) {
        val category = context.requiredTestClass.getAnnotation(DisplayName::class.java)?.value ?: context.requiredTestClass.simpleName
        tests[context.uniqueId] = category + "\u0000" + context.displayName.removeSuffix("()") to status
    }

    /**
     * Runs [block] on [receiver], which must throw [T], and records the frames of what it threw, down to the test's
     * own code, together with the receiver's types and the declarations of [method].
     */
    inline fun <reified T : Throwable, R : Any> trace(call: String, receiver: R, method: String, noinline block: (R) -> Unit): Array<StackTraceElement> =
        trace(T::class.java, call, receiver, method, block)

    fun <R : Any> trace(expected: Class<out Throwable>, call: String, receiver: R, method: String, block: (R) -> Unit): Array<StackTraceElement> =
        thrown(expected, call, receiver, method) { block(receiver) }.stackTrace

    /**
     * Runs [block] on [receiver], which must throw [T], records the frames of what it threw, and returns it. The report
     * also shows the receiver's types and the declarations of [method], by default the last method named in [call].
     */
    inline fun <reified T : Throwable, R : Any> thrown(call: String, receiver: R, method: String? = null, noinline block: (R) -> Unit): T =
        T::class.java.cast(thrown(T::class.java, call, receiver, method) { block(receiver) })

    /** Runs [block], which must throw [T], records the frames of what it threw, and returns it. */
    inline fun <reified T : Throwable> thrown(call: String, noinline block: () -> Unit): T =
        T::class.java.cast(thrown(T::class.java, call, null, null, block))

    fun thrown(expected: Class<out Throwable>, call: String, receiver: Any?, method: String?, block: () -> Unit): Throwable {
        val thrown = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
        if (thrown == null || !expected.isInstance(thrown)) {
            throw AssertionError("$call: expected ${expected.name}, but " + (thrown?.let { "got $it" } ?: "nothing was thrown"), thrown)
        }
        record(call, thrown, receiver, method)
        return thrown
    }

    /** Records the frames of [thrown], and of its causes, which [call] returned or which the test caught itself. */
    fun record(call: String, thrown: Throwable, receiver: Any? = null, method: String? = null) {
        val frames = mutableListOf<String>()
        var current: Throwable? = thrown
        while (current != null) {
            if (current !== thrown) {
                frames += CAUSE + current
            }
            frames += current.stackTrace.takeWhile { !it.className.startsWith(testClassName) }.take(8).map { "${it.className}.${it.methodName}" }
            current = current.cause?.takeIf { it !== current }
        }
        val types = receiver?.let { hierarchyOf(it.javaClass) }.orEmpty()
        val name = method ?: Regex("""\.(\w+)\(""").findAll(call).lastOrNull()?.groupValues?.get(1)
        observations += Observation(
            category,
            test,
            call,
            frames,
            types.map { TypeInfo.of(it) },
            types.flatMap { c -> c.declaredMethods.filter { it.name == name }.map { Declaration.of(c, it) } },
        )
    }

    /** Records frames the test read from elsewhere, e.g. from the output of a JVM of its own, top first. */
    fun record(call: String, frames: List<String>) {
        observations += Observation(category, test, call, frames, emptyList(), emptyList())
    }

    override fun afterAll(context: ExtensionContext) {
        // Also called for each @Nested class: write once, after the top-level class.
        if (context.requiredTestClass.enclosingClass != null) {
            return
        }
        val name = context.requiredTestClass.simpleName
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("$name.json"), json())
        Files.writeString(directory.resolve("$name.html"), html())
        Files.writeString(directory.resolve("index.html"), index())
    }

    /** Lists the reports in [directory], with their titles and results. */
    private fun index(): String {
        val reports = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().let { n -> n.endsWith(".html") && n != "index.html" } }.sorted().toList()
        }
        val items = reports.joinToString("") { file ->
            val text = Files.readString(file)
            val title = Regex("<title>(.*?)</title>").find(text)?.groupValues?.get(1) ?: file.fileName.toString()
            val passed = Regex("<span>(\\d+/\\d+ passed)</span>").find(text)?.groupValues?.get(1).orEmpty()
            "<li><a href=\"${file.fileName}\">$title</a> <span>$passed</span></li>"
        }
        return TEMPLATE
            .replace("{nav}", "")
            .replace("{title}", INDEX_TITLE)
            .replace("{intro}", "One report per test class; run the tests to update them.")
            .replace("{meta}", "<span>Updated ${Instant.now()}</span>")
            .replace("{toc}", items)
            .replace("{count}", "${reports.size} reports")
            .replace("{sections}", "")
    }

    private fun json(): String = observations.joinToString(",\n", "[\n", "\n]\n") { o ->
        "{\"category\": ${quote(o.category)}, \"test\": ${quote(o.test)}, \"call\": ${quote(o.call)}, " +
            "\"frames\": ${list(o.frames)}, \"types\": ${list(o.types.map { it.kind + " " + it.name + typeSuffix(it) })}, " +
            "\"declarations\": ${list(o.declarations.map { it.toString() })}}"
    }

    private fun typeSuffix(type: TypeInfo) = if (type.supertypes.isEmpty()) "" else " : " + type.supertypes.joinToString(", ")

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun list(items: List<String>) = items.joinToString(", ", "[", "]") { quote(it) }

    private fun html(): String {
        val source = source()
        val position = { text: String -> source.indexOf(text).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        val results = tests.values.map { (key, status) -> key.split("\u0000").let { Triple(it[0], it[1], status) } }
            .sortedBy { position(it.second) }
        val categories = results.map { it.first }.distinct().sortedBy { position(it) }
        val passed = results.count { it.third == "passed" }
        val byTest = observations.groupBy { it.category + "\u0000" + it.test }
        var number = 0
        val sections = categories.mapIndexed { index, category ->
            val cases = results.filter { it.first == category }.joinToString("\n") { (_, test, status) ->
                number++
                val calls = byTest[category + "\u0000" + test].orEmpty().joinToString("\n") { o ->
                    "<pre class=\"kt\"><code>${kotlin(declarations.render(o), o.call)}</code></pre>\n" +
                        "<div class=\"lbl\">Frames the test caught, top first</div><ol class=\"frames\">" +
                        o.frames.joinToString("") { frame(it) } + "</ol>"
                }
                "<article class=\"case\"><header><h3><span class=\"num\">$number</span>${escape(test.replaceFirstChar { it.uppercase() })}</h3>" +
                    "<span class=\"pill $status\">$status</span></header>\n$calls</article>"
            }
            "<section class=\"cat\" id=\"k$index\"><h2>${escape(category)}</h2>\n$cases</section>"
        }
        val toc = categories.mapIndexed { index, category ->
            "<li><a href=\"#k$index\">${escape(category)}</a> <span>${results.count { it.first == category }}</span></li>"
        }.joinToString("")
        return TEMPLATE
            .replace("{nav}", "<a href=\"index.html\">← $INDEX_TITLE</a>")
            .replace("{title}", escape(title))
            .replace("{meta}", "<span>Run ${Instant.now()}</span><span>Java ${System.getProperty("java.version")}</span><span>$passed/${results.size} passed</span>")
            .replace("{toc}", toc)
            .replace("{count}", "${categories.size} categories")
            .replace("{intro}", intro())
            .replace("{sections}", sections.joinToString("\n"))
    }

    private fun intro(): String =
        "Each case is one test, run with the agent. The frames are copied from the exception the test caught, down to the " +
            "test's own code, followed by those of its causes. Where the test names the receiver, the Kotlin declarations " +
            "are written out from reflection on it in the same run: its classes and interfaces, and every declaration " +
            "of the called method. Constructor arguments and method bodies are left out as <code>…</code>." +
            if (entries == null) "" else " The entry comments come from the configuration file."

    /** The declarations, with the comments and the call marked. */
    private fun kotlin(text: String, call: String): String =
        escape(text).replace(Regex("//[^\n]*")) { "<span class=\"c\">${it.value}</span>" }
            .replace(escape(call), "<span class=\"call\">${escape(call)}</span>")

    /**
     * The test class's source, if it is where Gradle keeps it, to show the tests in the order they are written: JUnit
     * runs them in an order of its own. Without it, the report keeps that order.
     */
    private fun source(): String {
        val file = Path.of("src/test/kotlin", testClassName.replace('.', '/') + ".kt")
        return if (Files.exists(file)) Files.readString(file) else ""
    }

    private fun frame(frame: String): String {
        if (frame.startsWith(CAUSE)) {
            return "<li class=\"cause\"><code>${escape(frame)}</code></li>"
        }
        val augmented = '{' in frame
        val text = escape(frame.removePrefix("$basePackage.")).replace(Regex("\\{[^}]*}")) { "<mark>${it.value}</mark>" }
        return "<li class=\"${if (augmented) "aug" else "plain"}\"><code>$text</code><span class=\"tag\">${if (augmented) "id added" else "unchanged"}</span></li>"
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val CAUSE = "Caused by: "
        const val INDEX_TITLE = "Stack Trace Frame reports"
        val TEMPLATE = FrameReport::class.java.getResource("frame-report.html")!!.readText()
    }
}
