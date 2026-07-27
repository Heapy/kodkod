package io.heapy.kodkod

/**
 * The raw byte transport beneath [DockerApi]. Given a fully-formed request, it returns the raw
 * HTTP/1.1 response bytes (status line + headers + body, body still chunked if the server chunked
 * it) — exactly what [DockerApi]'s parser expects.
 *
 * Production talks to a unix socket ([UnixSocketTransport]); tests inject a recording
 * ([RecordingDockerTransport]) or replay ([ReplayDockerTransport]) implementation so the orchestration
 * can be exercised against captured real-Docker responses without a live daemon.
 */
interface DockerTransport {
    /**
     * @param method        HTTP verb (`GET`, `POST`, `DELETE`).
     * @param path          request target including query string, e.g. `/containers/json?all=false&filters=...`.
     * @param body          request body bytes, or `null`.
     * @param headers       extra request headers (`Content-Type`, `X-Registry-Auth`, ...).
     * @param readTimeoutMs idle read timeout, in milliseconds.
     * @return the raw, not-yet-parsed response bytes.
     */
    fun exchange(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
        readTimeoutMs: Long,
    ): ByteArray
}
