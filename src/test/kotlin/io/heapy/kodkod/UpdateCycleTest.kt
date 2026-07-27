package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.locks.ReentrantLock

/**
 * `main`'s update cycle: which half of [Updater] runs under the shared lock, and what a cycle still
 * has to do when the read half fails. Neither is visible from [Updater] alone — the split only means
 * anything through the [Runnable] that schedules it.
 */
class UpdateCycleTest {
    private val clock = FakeClock()


    private fun config(): Config = Config.fromEnv(mapOf("KODKOD_UPDATE_MONITOR_ALL" to "true")::get)

    private fun updater(docker: DockerClient) = Updater(docker, config(), selfId = null, clock, clock)

    /** A stale `web`, the smallest cycle that both plans and mutates. */
    private fun staleWeb(docker: FakeDockerClient) {
        docker.listed += jsonObj("""{"Id":"web","Names":["/web"],"State":"running","Labels":{}}""")
        docker.containers["web"] = jsonObj(
            """{"Name":"/web","Image":"sha256:old","Config":{"Image":"nginx:1.27","Labels":{}},
               "HostConfig":{},"NetworkSettings":{"Networks":{}}}""",
        )
        docker.images["sha256:old"] = jsonObj("""{"Id":"sha256:old","Config":{},"RepoDigests":[]}""")
        docker.images["nginx:1.27"] = jsonObj("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")
    }

    /**
     * The reason the halves were split at all: `plan` waits on the registry — ten minutes of permitted
     * idle time per image — and holding the cycle lock through that keeps autoheal from restarting
     * anything for the whole time, with nothing logged to say why.
     */
    @Test
    fun only_the_mutating_half_of_the_cycle_takes_the_lock() {
        val lock = ReentrantLock()
        val docker = FakeDockerClient()
        staleWeb(docker)
        val heldDuring = mutableMapOf<String, Boolean>()
        val watched = object : DockerClient by docker {
            override fun pull(fromImage: String, tag: String, registryAuth: String?, platform: String?) {
                heldDuring["pull"] = lock.isLocked
                docker.pull(fromImage, tag, registryAuth, platform)
            }

            override fun create(name: String, body: JsonObject, platform: String?): String {
                heldDuring["create"] = lock.isLocked
                return docker.create(name, body, platform)
            }
        }

        updateCycle(lock, updater(watched)).run()

        assertEquals(false, heldDuring["pull"], "the pull is where all the waiting is: ${docker.ops}")
        assertEquals(true, heldDuring["create"], "and every mutation is serialized against autoheal: ${docker.ops}")
        assertFalse(lock.isLocked, "the lock has to be given back when the cycle ends")
    }

    /**
     * A cycle that could not decide what to update still has one thing it must do: a container parked
     * under its `_kodkod_old_` backup name by a process that died mid-recreate is a service that is
     * down right now, and "the daemon failed one listing" is no reason to leave it there for another
     * interval.
     */
    @Test
    fun a_planning_failure_does_not_take_the_reconcile_with_it() {
        val lock = ReentrantLock()
        val docker = FakeDockerClient()
        docker.listed += jsonObj("""{"Id":"web-old","Names":["/web_kodkod_old_web-old"],"State":"exited","Labels":{}}""")
        docker.containers["web-old"] = jsonObj(
            """{"Name":"/web_kodkod_old_web-old","Config":{},"HostConfig":{},
               "NetworkSettings":{"Networks":{}},"State":{"Running":false}}""",
        )

        updateCycle(lock, updater(NoDiscovery(docker))).run()

        assertEquals(
            listOf("rename:web-old->web", "start:web-old"), docker.ops,
            "the orphan has to be recovered even though planning failed: ${docker.ops}",
        )
    }

    @Test
    fun a_planning_failure_is_reported_rather_than_cancelling_the_schedule() {
        val docker = FakeDockerClient()

        val log = captureLog { updateCycle(ReentrantLock(), updater(NoDiscovery(docker))).run() }

        assertTrue(
            log.contains("planning failed"),
            "scheduleWithFixedDelay drops a task whose exception escapes — it has to be caught and named",
        )
    }
}

/**
 * A [FakeDockerClient] whose *discovery* listing fails (the daemon dropping the connection mid-cycle)
 * while every other listing still works — so a planning failure can be told apart from a daemon that
 * answers nothing at all.
 */
private class NoDiscovery(private val delegate: FakeDockerClient) : DockerClient by delegate {
    override fun listContainers(all: Boolean, filters: Map<String, List<String>>): JsonArray {
        if ("status" in filters) throw DockerException(500, "fake: discovery listing failed")
        return delegate.listContainers(all, filters)
    }
}
