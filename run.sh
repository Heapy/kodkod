#!/usr/bin/env bash
set -e

# === Load .env defaults ===
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"
fi

# === Configuration ===
DEFAULT_JDK_VERSION="${KODKOD_JDK_VERSION:-25}"
JDK_VERSION=$DEFAULT_JDK_VERSION
RECREATE_FLAG=false
LOCAL_FLAG="${KODKOD_LOCAL:-false}"

# === Parse Arguments ===
while [[ $# -gt 0 ]]; do
  case $1 in
    --jdk=*)
      JDK_VERSION="${1#*=}"
      shift
      ;;
    --local)
      LOCAL_FLAG=true
      shift
      ;;
    --recreate)
      RECREATE_FLAG=true
      shift
      ;;
    --help)
      echo "Usage: kodkod [--jdk=17|21|25] [--recreate] [--local]"
      echo ""
      echo "Options:"
      echo "  --jdk=VERSION    Use specific JDK version (17, 21, or 25). Default: 25"
      echo "  --recreate       Stop and remove existing container, then create new one"
      echo "  --local          Use local 'kodkod:latest' image instead of ghcr.io"
      echo "  --help           Show this help message"
      echo ""
      echo "Examples:"
      echo "  kodkod                    # Create or reuse container with JDK 25"
      echo "  kodkod --jdk=21           # Create or reuse container with JDK 21"
      echo "  kodkod --recreate         # Recreate container with default JDK"
      echo "  kodkod --recreate --jdk=17  # Recreate container with JDK 17"
      echo "  kodkod --local            # Use locally built image"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: kodkod [--jdk=17|21|25] [--recreate] [--local]"
      echo "Run 'kodkod --help' for more information"
      exit 1
      ;;
  esac
done

# === Validate JDK Version ===
if [[ ! "$JDK_VERSION" =~ ^(17|21|25)$ ]]; then
  echo "Error: Invalid JDK version '$JDK_VERSION'"
  echo "Supported versions: 17, 21, 25"
  exit 1
fi

# === Map JDK Version to Full Version String ===
case $JDK_VERSION in
  17) JAVA_FULL_VERSION="17.0.18-librca" ;;
  21) JAVA_FULL_VERSION="21.0.10-librca" ;;
  25) JAVA_FULL_VERSION="25.0.2-librca" ;;
esac

# === Generate Container Name ===
DIR_NAME=$(basename "$PWD" | tr -cd '[:alnum:]-_' | tr '[:upper:]' '[:lower:]')
DIR_PATH=$(pwd)
PATH_HASH=$(echo -n "$DIR_PATH" | shasum -a 256 | cut -c1-5)
CONTAINER_NAME="kodkod-${DIR_NAME}-${PATH_HASH}"
if [ "$LOCAL_FLAG" = true ]; then
  IMAGE_TAG="kodkod:latest"
else
  IMAGE_TAG="ghcr.io/heapy/kodkod:latest"
fi

# === Detect Host User ===
HOST_UID=$(id -u)
HOST_GID=$(id -g)

# === Check if Image Exists ===
if ! docker image inspect "$IMAGE_TAG" >/dev/null 2>&1; then
  echo "Error: Docker image '$IMAGE_TAG' not found"
  echo ""
  echo "Please build the image first:"
  echo "  docker build -t $IMAGE_TAG ."
  echo ""
  echo "The image contains all JDK versions (17, 21, 25)."
  echo "Use --jdk flag to select which version to use at runtime."
  exit 1
fi

# === Recreate Container if Requested ===
if [ "$RECREATE_FLAG" = true ]; then
  echo "Recreating container: $CONTAINER_NAME"
  docker stop "$CONTAINER_NAME" 2>/dev/null || true
  docker rm "$CONTAINER_NAME" 2>/dev/null || true
fi

# === Check if Container Exists ===
EXISTING_CONTAINER=$(docker ps -a --filter "name=^${CONTAINER_NAME}$" --format '{{.Names}}')

if [ -n "$EXISTING_CONTAINER" ]; then
  # Container exists, check if running
  RUNNING_CONTAINER=$(docker ps --filter "name=^${CONTAINER_NAME}$" --format '{{.Names}}')

  if [ -n "$RUNNING_CONTAINER" ]; then
    # Container is running, exec into it
    echo "Attaching to running container: $CONTAINER_NAME"
    docker exec -it "$CONTAINER_NAME" bash
  else
    # Container exists but stopped, start and attach
    echo "Starting stopped container: $CONTAINER_NAME"
    docker start -ai "$CONTAINER_NAME"
  fi
else
  # Container doesn't exist, create it
  echo "Creating new container: $CONTAINER_NAME (JDK $JDK_VERSION)"

  # Ensure ~/.kodkod cache directory exists
  mkdir -p "${HOME}/.kodkod"

  docker create -it \
    --name "$CONTAINER_NAME" \
    --user="${HOST_UID}:${HOST_GID}" \
    --label kodkod=true \
    --label kodkod.project.path="$PWD" \
    --label kodkod.jdk.version="$JDK_VERSION" \
    -v "${PWD}:/workspace" \
    -v "${HOME}/.kodkod:/.kodkod" \
    -e ANTHROPIC_API_KEY="${KODKOD_ANTHROPIC_API_KEY:-${ANTHROPIC_API_KEY:-}}" \
    -e OPENAI_API_KEY="${KODKOD_OPENAI_API_KEY:-${OPENAI_API_KEY:-}}" \
    -e GEMINI_API_KEY="${KODKOD_GEMINI_API_KEY:-${GEMINI_API_KEY:-}}" \
    -e GOOGLE_API_KEY="${KODKOD_GOOGLE_API_KEY:-${GOOGLE_API_KEY:-}}" \
    -e JAVA_HOME="/opt/sdkman/candidates/java/${JAVA_FULL_VERSION}" \
    -e JAVA_TOOL_OPTIONS="-Duser.home=/home/kodkod" \
    -e GRADLE_USER_HOME="/.kodkod/gradle" \
    -e MAVEN_HOME="/.kodkod/m2" \
    -e NPM_CONFIG_CACHE="/.kodkod/npm" \
    -e PIP_CACHE_DIR="/.kodkod/pip" \
    -e UV_CACHE_DIR="/.kodkod/uv" \
    -e CLAUDE_CONFIG_DIR="/.kodkod/config/claude" \
    -e PATH="/opt/sdkman/candidates/java/${JAVA_FULL_VERSION}/bin:/opt/sdkman/candidates/gradle/current/bin:/opt/sdkman/candidates/kotlin/current/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    -w /workspace \
    "$IMAGE_TAG" \
    bash

  # Start and attach to the new container
  docker start -ai "$CONTAINER_NAME"
fi
