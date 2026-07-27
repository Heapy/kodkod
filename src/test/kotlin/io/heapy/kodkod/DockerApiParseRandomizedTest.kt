package io.heapy.kodkod

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Randomized properties of the hand-rolled HTTP/1.1 parsing in [DockerApi].
 *
 * The example-based tests next door pin the shapes somebody thought of. These pin the shapes nobody
 * thought of: the parser reads bytes off a socket, and every one of its callers treats what comes
 * back as the daemon's word — a truncated container listing read as `[]` is "there is nothing to
 * update", and a truncated image inspect read as `RepoTags: []` is "nobody else wants this image,
 * delete it". So the parser is only allowed two outcomes, and "a short answer that looks whole" is
 * not one of them.
 *
 * Every case is driven by a seeded [Random] and the seed is printed on failure, so a red run here is
 * reproducible by pinning that one seed.
 */
class DockerApiParseRandomizedTest {
    private companion object {
        /** How many random worlds each property explores. Cheap: these are pure byte-array functions. */
        const val ROUNDS = 500
    }

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.US_ASCII)

    /** Encode [payload] the way a daemon would, splitting it into chunks of the sizes [random] picks. */
    private fun chunkEncode(payload: ByteArray, random: Random): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        while (pos < payload.size) {
            val size = minOf(payload.size - pos, random.nextInt(1, 17))
            out.write(bytes(size.toString(16)))
            out.write(bytes("\r\n"))
            out.write(payload, pos, size)
            out.write(bytes("\r\n"))
            pos += size
        }
        out.write(bytes("0\r\n\r\n"))
        return out.toByteArray()
    }

    /** Runs [body] for [ROUNDS] seeds, naming the seed that failed instead of "expected true, was false". */
    private fun forEachSeed(body: (Random) -> Unit) {
        for (seed in 0 until ROUNDS) {
            try {
                body(Random(seed))
            } catch (e: Throwable) {
                throw AssertionError("seed $seed: ${e.message}", e)
            }
        }
    }

    /**
     * What the daemon chunked, the parser must un-chunk — whatever chunk sizes it happened to use.
     * Chunk boundaries are the daemon's business (they follow its write buffer), so no caller may
     * depend on them and no decode may lose a byte at one.
     */
    @Test
    fun whatever_the_chunking_the_payload_survives_it() {
        forEachSeed { random ->
            val payload = random.nextBytes(random.nextInt(0, 512))
            assertArrayEquals(payload, DockerApi.dechunk(chunkEncode(payload, random)))
        }
    }

    /** The same, one layer up: through a full response, with the status and headers the daemon sends. */
    @Test
    fun a_whole_response_round_trips_chunked_or_not() {
        forEachSeed { random ->
            val payload = random.nextBytes(random.nextInt(0, 512))
            val status = random.nextInt(100, 600)
            val chunked = random.nextBoolean()
            val head = buildString {
                append("HTTP/1.1 $status Whatever\r\n")
                append("Api-Version: 1.4${random.nextInt(0, 10)}\r\n")
                // The header is matched case-insensitively, so the daemon's exact spelling must not matter.
                if (chunked) append(if (random.nextBoolean()) "Transfer-Encoding: chunked\r\n" else "transfer-encoding: CHUNKED\r\n")
                append("\r\n")
            }
            val raw = bytes(head) + if (chunked) chunkEncode(payload, random) else payload

            val parsed = DockerApi.parse(raw)

            assertEquals(status, parsed.status)
            assertArrayEquals(payload, parsed.body)
        }
    }

    /**
     * The property the whole class exists for: a chunked body cut short anywhere before its
     * terminator must raise, never return the prefix that arrived. The terminating `0\r\n` is the
     * daemon saying "that was all of it" — everything earlier is a connection that died mid-sentence.
     */
    @Test
    fun a_chunked_body_cut_anywhere_before_its_terminator_is_an_error_not_a_short_answer() {
        forEachSeed { random ->
            val payload = random.nextBytes(random.nextInt(1, 256))
            val encoded = chunkEncode(payload, random)
            // The last five bytes are "0\r\n\r\n"; keeping "0\r\n" is already a complete answer.
            val completeAt = encoded.size - 2

            val cut = random.nextInt(0, completeAt)
            val truncated = encoded.copyOfRange(0, cut)
            try {
                val decoded = DockerApi.dechunk(truncated)
                fail<Unit>(
                    "cutting ${encoded.size - cut} bytes off a ${encoded.size}-byte body returned " +
                        "${decoded.size} bytes instead of raising: a caller reads that as the whole answer",
                )
            } catch (_: DockerException) {
                // correct: a truncated body is an error
            }

            assertArrayEquals(
                payload, DockerApi.dechunk(encoded.copyOfRange(0, completeAt)),
                "a body whose terminator arrived is complete, trailing CRLF or not",
            )
        }
    }

    /**
     * Arbitrary bytes must produce either an answer or a [DockerException] — never anything else.
     * Anything else means the failure surfaces far from here, wearing a type no caller catches:
     * `Updater` treats a `DockerException` as "this container could not be checked this cycle" and
     * carries on, while an `IndexOutOfBoundsException` takes the whole cycle down.
     */
    @Test
    fun no_byte_sequence_makes_the_parser_throw_something_a_caller_would_not_expect() {
        forEachSeed { random ->
            val raw = random.nextBytes(random.nextInt(0, 256))
            expectOnlyDockerException("parse", raw) { DockerApi.parse(raw) }
            expectOnlyDockerException("dechunk", raw) { DockerApi.dechunk(raw) }
        }
    }

    /**
     * The same guarantee where it is most likely to break: bytes that are *nearly* a valid response,
     * so the parser gets far enough in to do arithmetic on numbers an attacker-shaped daemon chose.
     */
    @Test
    fun neither_does_a_response_that_is_almost_valid() {
        forEachSeed { random ->
            val payload = random.nextBytes(random.nextInt(1, 64))
            val encoded = chunkEncode(payload, random)
            val raw = bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n") + encoded

            val corrupted = raw.copyOf()
            repeat(random.nextInt(1, 4)) {
                corrupted[random.nextInt(corrupted.size)] = random.nextInt(0, 256).toByte()
            }
            expectOnlyDockerException("parse", corrupted) { DockerApi.parse(corrupted) }

            // And the chunk-size token specifically: it is the one field the parser does maths with.
            for (token in listOf("-1", "-0", "7fffffff", "80000000", "ffffffffff", "", " ", "+2", "0x3", "3;ext")) {
                val handmade = bytes("$token\r\nabc\r\n0\r\n\r\n")
                expectOnlyDockerException("dechunk size '$token'", handmade) { DockerApi.dechunk(handmade) }
            }
        }
    }

    private fun expectOnlyDockerException(what: String, raw: ByteArray, call: () -> Any) {
        try {
            call()
        } catch (_: DockerException) {
            // the one failure every caller is written against
        } catch (e: Throwable) {
            throw AssertionError(
                "$what threw ${e::class.simpleName} (${e.message}) instead of DockerException " +
                    "on ${raw.size} bytes: ${raw.joinToString(" ") { "%02x".format(it) }.take(200)}",
                e,
            )
        }
    }
}
