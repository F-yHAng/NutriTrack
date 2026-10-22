package com.example.nutritrack

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveLoggingBoundaryTest {
    @Test
    fun productionSources_doNotPrintOrLogSensitiveValues() {
        val sourceDirectory = File(moduleDirectory(), "src/main/java")
        val sources = sourceDirectory.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .toList()

        assertTrue("No production sources found", sources.isNotEmpty())
        sources.forEach { file ->
            val source = file.readText()
            assertFalse("${file.name} must not call println", PRINTLN.containsMatchIn(source))
            assertFalse("${file.name} must not print stack traces", PRINT_STACK_TRACE.containsMatchIn(source))

            extractLogCalls(source).forEach { call ->
                assertFalse(
                    "${file.name} logs sensitive or exception data: $call",
                    SENSITIVE_LOG_VALUE.containsMatchIn(call)
                )
            }
        }
    }

    private fun extractLogCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        LOG_CALL.findAll(source).forEach { match ->
            val start = match.range.first
            val openParenthesis = source.indexOf('(', start)
            var depth = 0
            for (index in openParenthesis until source.length) {
                when (source[index]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) {
                            calls += source.substring(start, index + 1)
                            break
                        }
                    }
                }
            }
        }
        return calls
    }

    private fun moduleDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return if (File(workingDirectory, "src/main/java").isDirectory) {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
    }

    private companion object {
        val PRINTLN = Regex("""\b(?:System\.(?:out|err)\.)?println\s*\(""")
        val PRINT_STACK_TRACE = Regex("""\.printStackTrace\s*\(""")
        val LOG_CALL = Regex("""(?:android\.util\.)?Log\.[a-z]+\s*\(""")
        val SENSITIVE_LOG_VALUE = Regex(
            """\b(password|phone(?:Number)?|patient|questionnaire|intake|prompt|response|throwable|exception|\.message)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
