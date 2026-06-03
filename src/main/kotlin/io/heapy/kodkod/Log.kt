package io.heapy.kodkod

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal stdout logger — avoids pulling in SLF4J/Logback for a single-purpose daemon.
 * Lines look like: `2026-06-03 21:15:00 [WARN ] message`.
 */
object Log {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun emit(level: String, message: String) {
        val line = "${LocalDateTime.now().format(format)} [$level] $message"
        synchronized(this) { println(line) }
    }

    fun info(message: String) = emit("INFO ", message)
    fun warn(message: String) = emit("WARN ", message)
    fun error(message: String) = emit("ERROR", message)
}
