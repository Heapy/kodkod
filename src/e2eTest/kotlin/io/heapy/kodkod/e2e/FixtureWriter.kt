package io.heapy.kodkod.e2e

import io.heapy.kodkod.CapturedExchange
import io.heapy.kodkod.FixtureIndex
import io.heapy.kodkod.FixtureLabel
import io.heapy.kodkod.FixtureManifest
import io.heapy.kodkod.FixtureMeta
import io.heapy.kodkod.RecordedExchange
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Writes a recorded scenario into the committed fixture corpus.
 *
 * The corpus is committed, so a half-written scenario is worse than no scenario at all: the replay
 * suite is strict and would fail on a truncated manifest, and an index entry pointing at a missing
 * directory breaks the whole run. Every write therefore lands in a staging directory (or a temp file)
 * first and only replaces the committed copy once it is complete — a recorder run that throws
 * midway leaves the previous fixture exactly as it was.
 *
 * [writeBytes] is the injection seam used by [FixtureWriterTest] to simulate a failing write; in
 * production it is plain [Files.write].
 */
internal class FixtureWriter(
    private val root: Path,
    private val writeBytes: (Path, ByteArray) -> Unit = { path, bytes -> Files.write(path, bytes) },
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Writes [exchanges] as `<root>/<label>/<scenario>/`, replacing any previously committed copy
     * only after every file is on disk. Returns the scenario directory.
     */
    fun writeScenario(label: String, scenario: String, exchanges: List<CapturedExchange>): Path {
        val labelDir = root.resolve(label)
        Files.createDirectories(labelDir)
        val staging = Files.createTempDirectory(labelDir, "$scenario.staging-")
        try {
            val records = exchanges.mapIndexed { i, ex ->
                val file = responseFileName(i + 1, ex.method, ex.path)
                writeBytes(staging.resolve(file), ex.responseBody)
                RecordedExchange(ex.method, ex.path, ex.requestBodySummary, ex.status, file)
            }
            writeBytes(
                staging.resolve("manifest.json"),
                json.encodeToString(FixtureManifest.serializer(), FixtureManifest(scenario, label, records))
                    .toByteArray(StandardCharsets.UTF_8),
            )
        } catch (e: Throwable) {
            staging.toFile().deleteRecursively()
            throw e
        }
        return promote(staging, labelDir.resolve(scenario))
    }

    /** Records which engine/compose versions produced `<label>`. */
    fun writeMeta(label: String, meta: FixtureMeta) {
        val labelDir = root.resolve(label)
        Files.createDirectories(labelDir)
        writeAtomically(
            labelDir.resolve("meta.json"),
            json.encodeToString(FixtureMeta.serializer(), meta).toByteArray(StandardCharsets.UTF_8),
        )
    }

    /**
     * Adds `<label>/<scenario>` to `index.json` (the replay suite enumerates the corpus from it,
     * because classpath directories cannot be listed portably). Additive: other labels and
     * scenarios are preserved, so re-recording one scenario never orphans the rest.
     */
    fun upsertIndex(label: String, scenario: String) {
        val indexFile = root.resolve("index.json")
        val current =
            if (Files.exists(indexFile)) {
                json.decodeFromString(FixtureIndex.serializer(), Files.readString(indexFile))
            } else {
                FixtureIndex()
            }
        val labels = current.labels.associateBy { it.label }.toMutableMap()
        val scenarios = ((labels[label]?.scenarios ?: emptyList()) + scenario).distinct().sorted()
        labels[label] = FixtureLabel(label, scenarios)

        Files.createDirectories(root)
        writeAtomically(
            indexFile,
            json.encodeToString(FixtureIndex.serializer(), FixtureIndex(labels.values.sortedBy { it.label }))
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    /** `0001.GET.containers-json.bin` — ordered, and readable enough to diff a re-recording by eye. */
    fun responseFileName(seq: Int, method: String, path: String): String {
        val hint = path.substringBefore('?').trim('/')
            .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).ifEmpty { "root" }
        return "%04d.%s.%s.bin".format(seq, method, hint)
    }

    /** Swaps [staging] in for [target], keeping the old copy until the move succeeds. */
    private fun promote(staging: Path, target: Path): Path {
        val backup = target.resolveSibling("${target.fileName}.backup-${UUID.randomUUID()}")
        val hadPrevious = Files.exists(target)
        if (hadPrevious) Files.move(target, backup)
        try {
            Files.move(staging, target)
        } catch (e: Throwable) {
            if (hadPrevious) Files.move(backup, target)
            staging.toFile().deleteRecursively()
            throw e
        }
        if (hadPrevious) backup.toFile().deleteRecursively()
        return target
    }

    /** A failed write must not truncate the committed file, so build it beside and then rename. */
    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val tmp = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            writeBytes(tmp, bytes)
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            throw e
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
