#!/bin/bash
# Create /etc/passwd and /etc/group entries for the container user if missing.
# This avoids "id: cannot find name for user ID ..." warnings when running
# with --user=UID:GID that doesn't exist inside the image.

CUR_UID=$(id -u)
CUR_GID=$(id -g)

if ! getent passwd "$CUR_UID" >/dev/null 2>&1; then
  if ! echo "kodkod:x:${CUR_UID}:${CUR_GID}::/home/kodkod:/bin/bash" >> /etc/passwd; then
    echo "entrypoint: warning: failed to add passwd entry for UID ${CUR_UID}" >&2
  fi
fi

if ! getent group "$CUR_GID" >/dev/null 2>&1; then
  if ! echo "kodkod:x:${CUR_GID}:" >> /etc/group; then
    echo "entrypoint: warning: failed to add group entry for GID ${CUR_GID}" >&2
  fi
fi

export HOME=/home/kodkod
if [ ! -d "$HOME" ]; then
  if ! mkdir -p "$HOME"; then
    echo "entrypoint: warning: failed to create home directory ${HOME}" >&2
  fi
fi

exec "$@"
