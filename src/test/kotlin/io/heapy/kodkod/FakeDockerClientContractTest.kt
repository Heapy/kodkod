package io.heapy.kodkod

/**
 * [FakeDockerClient] against the shared [DockerClientContract].
 *
 * This is the half that runs on every `./gradlew test`. Its twin
 * (`io.heapy.kodkod.e2e.DockerApiContractTest`) runs the same class against a real daemon, and the
 * pair is the point: a behaviour asserted here is a behaviour Docker was also made to demonstrate,
 * so the fake can no longer be more forgiving than the thing it stands in for.
 */
class FakeDockerClientContractTest : DockerClientContract() {
    override val client = FakeDockerClient()

    /** The fake keeps an unknown reference as-is, so this only has to look like a ref. */
    override val imageRef = "busybox:1.36"
}
