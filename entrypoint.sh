#!/bin/bash
# Create /etc/passwd and /etc/group entries for the container user if missing.
# This avoids "id: cannot find name for user ID ..." warnings when running
# with --user=UID:GID that doesn't exist inside the image.

CUR_UID=$(id -u 2>/dev/null)
CUR_GID=$(id -g 2>/dev/null)

if ! getent passwd "$CUR_UID" >/dev/null 2>&1; then
  echo "kodkod:x:${CUR_UID}:${CUR_GID}::/home/kodkod:/bin/bash" >> /etc/passwd 2>/dev/null || true
fi

if ! getent group "$CUR_GID" >/dev/null 2>&1; then
  echo "kodkod:x:${CUR_GID}:" >> /etc/group 2>/dev/null || true
fi

export HOME=/home/kodkod
mkdir -p "$HOME" 2>/dev/null || true

exec "$@"
