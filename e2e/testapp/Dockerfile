# Test image for kodkod's update scenarios.
#
# Build two variants from the SAME Dockerfile to simulate a published update:
#   docker build --target v1 --build-arg VARIANT=v1 -t 127.0.0.1:5000/testapp:latest e2e/testapp
#   docker build --target v2 --build-arg VARIANT=v2 -t 127.0.0.1:5000/testapp:latest e2e/testapp
#
# The variant is baked into ENV + LABEL — i.e. into the image *defaults*. The
# container created from this image overrides NEITHER, so for an update to be
# observable kodkod must adopt the NEW image's defaults rather than freezing the
# old container's resolved config. That is exactly the "subtract old image
# defaults" behaviour (plan fix 1) under test.
ARG VARIANT=v1
FROM busybox:1.36 AS base
ARG VARIANT
ENV APP_VARIANT=${VARIANT}
LABEL app.variant=${VARIANT}

# Self-contained health: the marker exists while healthy. Removing it (docker exec
# rm /tmp/healthy) flips the container to unhealthy — used by the autoheal scenario.
# v1/v2 intentionally have different healthcheck defaults so the update scenario can
# prove kodkod preserves partial user overrides without freezing the old image check.
CMD ["sh", "-c", "echo \"[testapp] live variant=$APP_VARIANT\"; touch /tmp/healthy /tmp/healthy-$APP_VARIANT; while true; do sleep 1; done"]

FROM base AS v1
HEALTHCHECK --interval=3s --timeout=2s --retries=2 --start-period=2s \
  CMD test -f /tmp/healthy && test -f /tmp/healthy-v1 || exit 1

FROM base AS v2
HEALTHCHECK --interval=3s --timeout=4s --retries=4 --start-period=2s \
  CMD test -f /tmp/healthy && test -f /tmp/healthy-v2 || exit 1
