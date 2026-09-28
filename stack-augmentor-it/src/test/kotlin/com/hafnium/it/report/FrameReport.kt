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
 * and call [trace] in the tests. The tests of one class must not run in parallel.
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

    fun <R : Any> trace(expected: Class<out Throwable>, call: String, receiver: R, method: String, block: (R) -> Unit): Array<StackTraceElement> {
        val thrown = try {
            block(receiver)
            null
        } catch (e: Throwable) {
            e
        }
        if (thrown == null || !expected.isInstance(thrown)) {
            throw AssertionError("$call: expected ${expected.name}, but " + (thrown?.let { "got $it" } ?: "nothing was thrown"), thrown)
        }
        val trace = thrown.stackTrace
        val frames = trace.takeWhile { !it.className.startsWith(testClassName) }.take(8).map { "${it.className}.${it.methodName}" }
        val types = hierarchyOf(receiver.javaClass)
        observations += Observation(
            category,
            test,
            call,
            frames,
            types.map { TypeInfo.of(it) },
            types.flatMap { c -> c.declaredMethods.filter { it.name == method }.map { Declaration.of(c, it) } },
        )
        return trace
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
            .replace("{title}", escape(title))
            .replace("{meta}", "<span>Run ${Instant.now()}</span><span>Java ${System.getProperty("java.version")}</span><span>$passed/${results.size} passed</span>")
            .replace("{toc}", toc)
            .replace("{count}", categories.size.toString())
            .replace("{sections}", sections.joinToString("\n"))
    }

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
        val augmented = '{' in frame
        val text = escape(frame.removePrefix("$basePackage.")).replace(Regex("\\{[^}]*}")) { "<mark>${it.value}</mark>" }
        return "<li class=\"${if (augmented) "aug" else "plain"}\"><code>$text</code><span class=\"tag\">${if (augmented) "id added" else "unchanged"}</span></li>"
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        val TEMPLATE = FrameReport::class.java.getResource("frame-report.html")!!.readText()
    }
}
