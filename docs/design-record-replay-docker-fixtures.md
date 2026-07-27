> **Status: superseded — implemented.** This is the original design note for the record/replay
> fixture harness, kept for the reasoning behind it. It is **not** a description of the shipped
> behaviour and disagrees with it in places (the index is required rather than optional, exhaustive
> consumption is asserted on every scenario, and the per-scenario op expectations have moved on).
> For how the harness works today see [E2E_TESTING.md](../E2E_TESTING.md) — recording flags, the
> corpus layout, and when to re-record — and [CLAUDE.md](../CLAUDE.md) for the rules that govern it.

# Record real Docker responses, replay them in versioned unit tests

## Context

Today the `Updater`/`Autoheal` orchestration is unit-tested with a hand-written
`FakeDockerClient` (`src/test/kotlin/io/heapy/kodkod/FakeDockerClient.kt`) that returns
*synthetic* JSON (`{"Id":"sha256:new",...}`). That proves the logic against shapes **we
imagined**, not the bytes a real Docker engine emits. Docker/compose JSON also drifts across
versions (e.g. the `com.docker.compose.depends_on` label format), and we have no regression
signal when it does.

Goal: add a **record mode** to the Docker client that captures the *real* raw responses Docker
returns into `src/test/resources`, then **replay** those fixtures through the real
`DockerApi` + `Updater`/`Autoheal` in plain (no-Docker) unit tests. Fixtures are **versioned by
docker engine + docker compose version**, so supporting a new version is purely additive — drop a
new fixture directory; existing versions keep being exercised and can't silently break.

Decisions already locked with the user:
- Record/replay at the **raw transport layer** (most faithful; also exercises `DockerApi` parsing).
- Recorder runs **in-process in `e2eTest` against the local Docker daemon** (`-Pkodkod.e2e.useCurrentDocker=true`); `DockerApi` stays unix-socket-only.
- Replay tests live in `test`, need **no Docker**, read committed fixtures from the classpath.
- Initial corpus: **update→recreate**, **update→no-op**, **autoheal→restart**, **compose deps→ordered**.

```
Updater / Autoheal            ── unchanged
      │  (DockerClient)
  DockerApi ── parse() ──┐    ── parse/dechunk moved to internal companion
      │                  │
  DockerTransport  ◄──────┴── RECORD (wrap socket) / REPLAY (in-memory)   ← NEW seam
      │
  unix socket → dockerd
```

---

## Part A — Transport seam (refactor `DockerApi`, behaviour-preserving)

**New file `src/main/kotlin/io/heapy/kodkod/DockerTransport.kt`** (public — `e2eTest` must construct impls):
```kotlin
interface DockerTransport {
    /** One HTTP/1.1 exchange; returns the RAW response bytes (status line + headers + body,
     *  still chunked if chunked) — exactly what DockerApi's parser expects. */
    fun exchange(method: String, path: String, body: ByteArray?,
                 headers: Map<String, String>, readTimeoutMs: Long): ByteArray
}
```

**New file `src/main/kotlin/io/heapy/kodkod/UnixSocketTransport.kt`** (public): move the socket
plumbing out of `DockerApi` verbatim — the `SocketChannel.open(UNIX)` connect, the head/body
write (`writeFully`), and `readUntilClose` (returns raw bytes). It implements
`DockerTransport.exchange` and no longer calls `parse()`.

**Edit `src/main/kotlin/io/heapy/kodkod/DockerApi.kt`:**
- Constructor becomes transport-based, with a socket convenience secondary (both public; `Main`
  and `DockerApiParseTest` keep using `DockerApi(socketPath)` unchanged):
  ```kotlin
  class DockerApi(private val transport: DockerTransport) : DockerClient {
      constructor(socketPath: String) : this(UnixSocketTransport(socketPath))
  ```
- `request(...)` loses the socket code and becomes:
  ```kotlin
  private fun request(method, path, body=null, headers=emptyMap(), readTimeoutMs=60_000): HttpResponse =
      parse(transport.exchange(method, path, body, headers, readTimeoutMs))
  ```
- Move `parse`, `dechunk`, `indexOf`, and the `CRLF`/`CRLF_CRLF` constants into an
  **`internal companion object`** (functions `internal`). `HttpResponse` stays `internal` nested.
  `request` still calls `parse(...)` (resolves to the companion). This lets
  `RecordingDockerTransport` (also in `main`) call `DockerApi.parse(raw)` with no throwaway instance.
- Delete the now-unused `socketPath` field, socket imports, `writeFully`, `readUntilClose`.

**Edit `src/test/kotlin/io/heapy/kodkod/DockerApiParseTest.kt`:** change `api.parse(...)` /
`api.dechunk(...)` → `DockerApi.parse(...)` / `DockerApi.dechunk(...)` (now companion). Keep
the rest; `internal` is visible to `test`.

Verify after Part A: `./gradlew test` and the live `./gradlew e2eTest` stay green — pure refactor.

---

## Part B — Record/replay types (`main`, public)

**New file `src/main/kotlin/io/heapy/kodkod/DockerRecording.kt`.** All types `public` because the
`e2eTest` recorder depends on `main` as an artifact and cannot see `internal`.

Serializable fixture DTOs (reuse `kotlinx.serialization.json`, already a `main` dependency):
```kotlin
@Serializable class RecordedExchange(val method: String, val path: String,
    val requestBodySummary: String?, val status: Int, val responseFile: String)
@Serializable class FixtureManifest(val scenario: String, val label: String, val exchanges: List<RecordedExchange>)
@Serializable class FixtureMeta(val dockerVersion: String, val apiVersion: String,
    val composeVersion: String, val recordedAt: String)
@Serializable class FixtureIndex(val labels: List<FixtureLabel>)
@Serializable class FixtureLabel(val label: String, val scenarios: List<String>)
```

`RecordingDockerTransport(delegate: DockerTransport)`:
- `exchange(...)`: `val raw = delegate.exchange(...)`; parse via `DockerApi.parse(raw)` to get
  `(status, body)`; append a `CapturedExchange(method, path, requestBodySummary = body?.let{"${it.size} bytes"}, status, responseBody = parsed.body)`; **return `raw`** so the production `DockerApi.parse` runs identically downstream.
- Exposes `val exchanges: List<CapturedExchange>` (public `CapturedExchange` carries the
  dechunked `responseBody: ByteArray` the recorder writes to disk).
- **Storage choice:** persist the *decoded* body + status (clean, readable, diffable JSON per
  fixture) rather than raw wire bytes with volatile `Date`/chunk framing. Chunked-wire parsing
  stays covered by `DockerApiParseTest`.

`ReplayDockerTransport(exchanges: List<RecordedExchange>, body: (file: String) -> ByteArray)`:
- **Matching:** key = `"$method $path"`, a **FIFO queue per key**. Request bodies are *outputs of
  the code under test* → never matched. Repeated `(method,path)` calls (e.g.
  `GET /images/<ref>/json` before vs after `pull`) dequeue successive recordings in order.
- `exchange(...)` dequeues for the key and returns a **synthesized** raw response
  `"HTTP/1.1 <status> X\r\nContent-Length: <n>\r\nConnection: close\r\n\r\n" + body` (fed back
  through `DockerApi.parse`).
- Unknown key → throw with the full set of recorded keys; exhausted key → throw "code made more
  calls than recorded — re-record". Optional `assertFullyConsumed()` to catch the *fewer-calls* case.

Add a tiny **`ReplayDockerTransportTest`** in `test` (in-memory fixture, no Docker) covering
FIFO-per-key ordering + the exhausted/unknown errors, so the matcher is trustworthy before any
real recording exists.

---

## Part C — Fixture format + versioning (`src/test/resources`)

```
src/test/resources/docker-fixtures/
  index.json                          # enumerates labels+scenarios (classpath dirs aren't listable)
  <label>/
    meta.json                         # docker/api/compose versions + recordedAt
    <scenario>/
      manifest.json                   # FixtureManifest: ordered RecordedExchange list
      0001.GET.containers-json.bin    # decoded response bodies, numbered by global order
      0007.POST.containers-create.bin
      ...
```
- **`<label>`** = `engine-<sanitize(dockerVersion)>_compose-<sanitize(composeVersion)>` where
  `sanitize` replaces any char outside `[A-Za-z0-9.]` with `-` (handles `+build`, `v` prefixes).
  Adding a new docker/compose combo = new directory + new `index.json` entry, **never touching
  existing labels** → that is the "support new versions without breaking existing" guarantee.
- Response files are raw bodies (binary-safe; handles the `pull` newline-delimited stream and
  `204`-empty bodies alike). Filenames are recorded in the manifest, so the loader never guesses.
- `.gitignore` ignores only `build/.gradle/.kotlin/.idea`, so `src/test/resources/**` commits normally.

---

## Part D — Recorder (`e2eTest`, opt-in, local Docker)

**Refactor:** promote `E2eHarness` (and `CommandResult`) in
`src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt` from `private` to **`internal`** (or move
to `E2eHarness.kt`) so the recorder file can reuse it. Add one helper:
```kotlin
fun composeVersion(): String = docker("compose", "version", "--short", check = false).output.trim()
```

**New file `src/e2eTest/kotlin/io/heapy/kodkod/e2e/DockerFixtureRecorder.kt`:**
- Gated so it never runs in normal CI e2e:
  `@EnabledIfSystemProperty(named = "kodkod.e2e.record", matches = "true")` (JUnit built-in) +
  the user runs `./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true -Pkodkod.e2e.record=true --tests '*DockerFixtureRecorder*'`.
- `@BeforeAll` calls `e2e.startDocker(); e2e.setup()` (builds registry + testapp v1).
- A `record(scenario) { block }` helper builds
  `DockerApi(RecordingDockerTransport(UnixSocketTransport("/var/run/docker.sock")))`, runs the
  scenario block (sets up containers via the harness, then runs the **real** `Updater`/`Autoheal`
  `runOnce()` in-process with `selfId = null`), then writes the response files + `manifest.json`,
  upserts `index.json`, writes `<label>/meta.json`, and in `finally` tears the stack down
  (`compose("<file>", "down", "-v")`). Output dir = `e2e.root.resolve("src/test/resources/docker-fixtures/...")`
  via `java.nio.file.Files` (host path, since `useCurrentDocker=true` runs the JVM on the host).
- **Reuse existing compose files, bringing up only target services** (the files include a
  `kodkod` service that would race the in-process runner): `compose("update","up","-d","app")`,
  `compose("autoheal","up","-d","app")`, `compose("deps","up","-d","web","db")`.
- Versions: read engine `Version`/`ApiVersion` from a throwaway `DockerApi(socket).version()`
  (kept out of scenario manifests — `runOnce` never calls `/version`), and `e2e.composeVersion()`.

Scenario setup (mirrors existing `KodkodE2eTest` flows; `cfg(...)` = `Config.fromEnv(map::get)`):
- **update-recreate:** `publishVariant("v1")`; `up -d app`; wait `variant == "v1"`;
  `publishVariant("v2")` (moves `:latest`); record `Updater(cfg(updateMonitorAll=true,cleanup=true)).runOnce()`.
  Captures list→inspect→inspectImage(old)→distribution/pull→inspectImage(new)→stop→rename→create→start→remove→removeImage.
- **update-noop:** `publishVariant("v1")`; `up -d app`; wait v1; **do not** publish v2; record
  `Updater(cfg(updateMonitorAll=true)).runOnce()` → registry-digest/pulled-same no-op (no create/stop).
- **autoheal-restart:** `up -d app`; wait `health=="healthy"`;
  `docker exec … rm -f /tmp/healthy`; **wait `health=="unhealthy"`** (so `listContainers(health=unhealthy)`
  returns it); record `Autoheal(cfg(autohealMonitorAll=true)).runOnce()` → list→restart.
- **deps-ordered:** `publishVariant("v1")`; `up -d web db`; wait db v1; `publishVariant("v2")`;
  record `Updater(cfg(updateMonitorAll=true)).runOnce()`. Captures the real `com.docker.compose.*`
  labels (the reason the compose-version axis exists) and the stop-reverse / start-forward ordering.

**Build:** add to the `e2eTest` task in `build.gradle.kts` (mirrors the existing `systemProperty` block):
```kotlin
systemProperty("kodkod.e2e.record", providers.gradleProperty("kodkod.e2e.record").orNull ?: "false")
```
No change to `test`; `src/test/resources` is already on its runtime classpath. No new dependencies.

---

## Part E — Replay tests (`test`, no Docker)

**New file `src/test/kotlin/io/heapy/kodkod/DockerReplayTest.kt`:**
- `@TestFactory fun replay(): List<DynamicTest>` reads `docker-fixtures/index.json` from the
  classpath (`classLoader.getResourceAsStream`). **If the index is absent → return `emptyList()`**
  (CI stays green before fixtures are seeded). A listed-but-missing fixture fails loudly.
- For each `(label, scenario)`: load `manifest.json`, build
  `ReplayDockerTransport(manifest.exchanges) { resource("docker-fixtures/$label/$scenario/$it").readBytes() }`,
  wrap `DockerApi(replay)` in a small **`OpLoggingClient`** and run the matching `runOnce()`.
- **`OpLoggingClient`** (test-only `DockerClient` decorator): delegates every call to the real
  `DockerApi(replay)` (so real parsing + `Id` extraction + orchestration run), and records mutating
  calls into an `ops` list using the **same string format as `FakeDockerClient`**
  (`stop:`/`start:`/`rename:`/`create:`/`remove:`/`connect:`/`removeImage:`/`restart:`/`pull:`) plus a
  `created` list — so the existing `assertOrder` helper and assertion idioms from `UpdaterTest` are
  reused verbatim. It also tracks `inspectContainer` results to map container id→`Name`, so ops read
  as `stop:e2e-deps-web-1` rather than raw ids (stable, readable assertions).
- **Per-scenario expectations encoded in the test** (version-independent — they're properties of
  kodkod's logic, not of any recording), applied to every label:
  - `update-recreate` → `assertOrder(ops, "pull:", "rename:", "create:", "start:", "remove:")`; the
    single `created` body's `Image` ends with `testapp:latest`.
  - `update-noop` → no `create:`/`stop:` ops.
  - `autoheal-restart` → exactly one op, a `restart:`.
  - `deps-ordered` → `assertOrder(ops, "stop:…web…", "stop:…db…", "create:…db…", "start:…web…")`;
    exactly one `create` (db), none for web.

---

## Risks / gotchas

1. **Replay determinism (holds):** ids the code uses in later request paths
   (`/containers/<id>/start`, `remove`, `connectNetwork`) all originate from recorded responses
   (`create` → `Id`), so generated paths equal recorded paths and the FIFO matches. Guaranteed
   because there's no live daemon and `selfId = null` (HOSTNAME never leaks in).
2. **Key exhaustion is a feature:** if a code change adds/removes/reorders Docker calls, replay
   throws (missing/exhausted) or leaves a queue non-empty → signal to **re-record**, not to patch
   the matcher. Treat fixtures as goldens.
3. **`pull` stream / error / non-2xx:** store bodies verbatim; a recorded `404` from
   `inspectDistribution` replays as `404` so `runCatching`→pull-fallback behaves identically. Keep
   the success pull-stream byte-exact (don't prettify — its terminating object matters).
4. **Compose version axis:** `deps-ordered` freezes whatever `com.docker.compose.depends_on` the
   live compose emitted. A future compose version that changes the label format and breaks
   `resolveLinks` will fail that version's replay — exactly the regression signal we want. The
   `kodkod.depends-on: db` fallback label in `compose.deps.yml` covers compose versions that omit it.
5. **Fixtures are re-recordable, not hand-portable:** they embed `127.0.0.1:5000`, image refs, and
   real ids. Don't rewrite them; re-record per version. The `<label>` isolates them.
6. **Visibility:** every type `e2eTest` constructs (`DockerTransport`, `UnixSocketTransport`,
   `RecordingDockerTransport`, `CapturedExchange`, the `@Serializable` DTOs, `DockerApi(transport)`)
   must be `public`. Only `HttpResponse`/`parse`/`dechunk`/`indexOf` stay `internal`.

---

## Implementation order

1. **Part A** transport seam + `DockerApiParseTest` tweak → run `test` + `e2eTest`, confirm green (pure refactor).
2. **Part B** record/replay types + DTOs + `ReplayDockerTransportTest` → `test` green.
3. **Part E** `DockerReplayTest` (empty index → no-op) + `OpLoggingClient` → lands green, guards loader.
4. **Part D** recorder + `kodkod.e2e.record` wiring + `E2eHarness` promotion. Record the four
   scenarios locally, commit `src/test/resources/docker-fixtures/**` + `index.json`. `DockerReplayTest`
   now exercises them in plain `./gradlew build`.

## Verification

- After A: `./gradlew test` green; existing `DockerApiParseTest`/`UpdaterTest`/`AutohealTest` unchanged behaviour.
- After B/C/E: `./gradlew test` green (replay no-ops with no fixtures; `ReplayDockerTransportTest` passes).
- Seed: `./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true -Pkodkod.e2e.record=true --tests '*DockerFixtureRecorder*'`
  with local Docker → writes `src/test/resources/docker-fixtures/<label>/...`; inspect/commit them.
- Final: `./gradlew test` now runs `DockerReplayTest` over the committed version label(s); each
  scenario's invariants pass against **real** Docker JSON. Adding another engine/compose version =
  re-run the recorder on that host → new `<label>` dir, existing ones untouched.
```
