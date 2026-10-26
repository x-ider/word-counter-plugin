package com.romaevt.wordcounter

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

// An opt-in diagnostic, not a timing-sensitive unit test or a whole-IDE memory measurement.
object CounterProfile {
    @Volatile private var result: Any? = null

    @JvmStatic fun main(args: Array<String>) {
        val output = Path.of(args[0])
        val fixture = CounterProfile::class.java.getResource("/article.mdx")!!.readText()
        val cases = mutableListOf(
            Triple("Article fixture", fixture, DocumentFormat.MDX),
            Triple("Large MDX", "<Note>Hello **world**, read [this guide](https://example.com).</Note>\n\n".repeat(8_000), DocumentFormat.MDX),
            Triple("Large TXT", "Hello world, this is a plain text paragraph.\n".repeat(12_000), DocumentFormat.PLAIN_TEXT),
            Triple("Large Markdown", "Hello **world**, read [this guide](https://example.com).\n\n".repeat(8_000), DocumentFormat.MARKDOWN),
        )
        args.getOrNull(1)?.let {
            val file = Path.of(it)
            val format = when (file.fileName.toString().substringAfterLast('.').lowercase()) {
                "txt", "text" -> DocumentFormat.PLAIN_TEXT
                "mdx" -> DocumentFormat.MDX
                "md", "markdown" -> DocumentFormat.MARKDOWN
                else -> error("Unsupported profile file: $file")
            }
            cases += Triple(file.fileName.toString(), Files.readString(file), format)
        }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported && bean.isCurrentThreadCpuTimeSupported)
        bean.isThreadAllocatedMemoryEnabled = true
        bean.isThreadCpuTimeEnabled = true
        val report = StringBuilder("Word Counter diagnostic\n")
        report.appendLine("Java ${System.getProperty("java.version")}; ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        report.appendLine("10 warmups, 20 measured iterations per operation; JFR enabled.")
        report.appendLine("Decimal units: 1 KB = 1,000 bytes; 1 MB = 1,000,000 bytes.")
        report.appendLine("Allocation is temporary plus retained bytes on the measuring thread, not live heap or IDE overhead.\n")
        fun measure(label: String, operation: () -> Any) {
            repeat(10) { result = operation() }
            val elapsed = mutableListOf<Double>()
            var allocated = 0L
            var cpu = 0L
            repeat(20) {
                val bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
                val startCpu = bean.currentThreadCpuTime
                val start = System.nanoTime()
                result = operation()
                elapsed += (System.nanoTime() - start) / 1_000_000.0
                cpu += bean.currentThreadCpuTime - startCpu
                allocated += bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes
            }
            elapsed.sort()
            report.appendLine(String.format(Locale.ROOT,
                "%s: median %.3f ms, p95 %.3f ms, mean CPU %.3f ms, mean allocated %s",
                label, (elapsed[9] + elapsed[10]) / 2, elapsed[18], cpu / 20_000_000.0, formatBytes(allocated / 20.0)))
        }
        for ((name, source, format) in cases) {
            val parsed = WordCounter.parse(source, format)
            check(parsed.counts == parsed.countSelections(listOf(SourceRange(0, source.length))))
            report.appendLine("$name: ${source.length} source UTF-16 units; ${parsed.counts}")
            report.appendLine("Cached source mappings: ${parsed.mappingStorageBytes} payload bytes (excludes source/extracted text and object overhead).")
            measure("  Parse + count") { WordCounter.parse(source, format) }
            val selection = listOf(SourceRange(source.length / 2, minOf(source.length / 2 + 80, source.length)))
            measure("  Cached selection (80 source units)") { parsed.countSelections(selection) }
            report.appendLine()
        }
        result = null
        Files.createDirectories(output)
        Files.writeString(output.resolve("summary.txt"), report)
        println(report)
    }

    internal fun formatBytes(bytes: Double): String = when {
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.2f MB (%.1f bytes)", bytes / 1_000_000, bytes)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.2f KB (%.1f bytes)", bytes / 1_000, bytes)
        else -> String.format(Locale.ROOT, "%.1f bytes", bytes)
    }
}
