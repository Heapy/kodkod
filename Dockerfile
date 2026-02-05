# AI Agent Development Environment
# Amazon Linux 2023 + JDK, Gradle, Kotlin via SDKMAN

# --- Stage 1: SDKMAN + JDKs (isolated layer, rebuilds only when SDK versions change) ---
FROM amazonlinux:2023 AS sdkman

ENV SDKMAN_DIR="/opt/sdkman"

RUN --mount=type=cache,id=dnf-sdkman,target=/var/cache/dnf \
    dnf update -y && \
    dnf install -y --allowerasing curl unzip zip tar gzip findutils which
RUN curl -s "https://get.sdkman.io" | bash
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && \
        sdk install java 21.0.10-librca && \
        sdk install java 17.0.18-librca && \
        sdk install java 25.0.2-librca && \
        sdk default java 25.0.2-librca && \
        sdk install gradle 9.3.1 && \
        sdk install kotlin 2.3.0"
RUN chmod -R 755 $SDKMAN_DIR
RUN rm -rf $SDKMAN_DIR/tmp

# --- Stage 2: CLI tools (fd, rg, ralphex) ---
FROM amazonlinux:2023 AS tools

ENV RIPGREP_VERSION=15.1.0 \
    FD_VERSION=10.3.0 \
    RALPHEX_VERSION=0.6.0

RUN --mount=type=cache,id=dnf-tools,target=/var/cache/dnf \
    dnf update -y && \
    dnf install -y --allowerasing curl tar gzip

# Install ripgrep (multi-arch)
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then RG_ARCH="aarch64-unknown-linux-gnu"; else RG_ARCH="x86_64-unknown-linux-musl"; fi && \
    curl -L https://github.com/BurntSushi/ripgrep/releases/download/${RIPGREP_VERSION}/ripgrep-${RIPGREP_VERSION}-${RG_ARCH}.tar.gz -o /tmp/ripgrep.tar.gz && \
    tar -xzf /tmp/ripgrep.tar.gz -C /tmp && \
    cp /tmp/ripgrep-${RIPGREP_VERSION}-${RG_ARCH}/rg /usr/local/bin/ && \
    chmod +x /usr/local/bin/rg

# Install fd (multi-arch)
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then FD_ARCH="aarch64-unknown-linux-gnu"; else FD_ARCH="x86_64-unknown-linux-musl"; fi && \
    curl -L https://github.com/sharkdp/fd/releases/download/v${FD_VERSION}/fd-v${FD_VERSION}-${FD_ARCH}.tar.gz -o /tmp/fd.tar.gz && \
    tar -xzf /tmp/fd.tar.gz -C /tmp && \
    cp /tmp/fd-v${FD_VERSION}-${FD_ARCH}/fd /usr/local/bin/ && \
    chmod +x /usr/local/bin/fd

# Install ralphex (multi-arch)
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then RX_ARCH="arm64"; else RX_ARCH="amd64"; fi && \
    curl -L https://github.com/umputun/ralphex/releases/download/v${RALPHEX_VERSION}/ralphex_${RALPHEX_VERSION}_linux_${RX_ARCH}.tar.gz -o /tmp/ralphex.tar.gz && \
    tar -xzf /tmp/ralphex.tar.gz -C /tmp && \
    mv /tmp/ralphex /usr/local/bin/ralphex && \
    chmod +x /usr/local/bin/ralphex

# --- Stage 3: Node.js via nvm ---
FROM amazonlinux:2023 AS nodejs

ENV NVM_DIR="/opt/nvm" \
    NODE_VERSION=24

RUN --mount=type=cache,id=dnf-nvm,target=/var/cache/dnf \
    dnf update -y && \
    dnf install -y --allowerasing curl tar gzip && \
    mkdir -p $NVM_DIR && \
    curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.4/install.sh | bash && \
    bash -c "source $NVM_DIR/nvm.sh && NVM_SYMLINK_CURRENT=true nvm install $NODE_VERSION"

# --- Stage 4: Final image ---
FROM amazonlinux:2023

LABEL org.opencontainers.image.source="https://github.com/umputun/kodkod" \
      org.opencontainers.image.description="AI Agent Development Environment — Amazon Linux 2023 with JDK, Gradle, Kotlin, Node.js, and AI CLI tools" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV SDKMAN_DIR="/opt/sdkman" \
    NVM_DIR="/opt/nvm"

# Install system dependencies
RUN --mount=type=cache,id=dnf-final,target=/var/cache/dnf \
    dnf update -y && \
    dnf install -y --allowerasing \
        git \
        curl \
        wget \
        tar \
        gzip \
        unzip \
        which \
        sudo \
        jq \
        python3 \
        python3-pip \
        gcc \
        make \
        tmux \
        zip \
        findutils \
        vim-minimal \
        procps-ng \
        less

# Copy pre-built SDKMAN with all SDKs from builder stage
COPY --from=sdkman /opt/sdkman /opt/sdkman

ENV JAVA_HOME="$SDKMAN_DIR/candidates/java/current" \
    GRADLE_HOME="$SDKMAN_DIR/candidates/gradle/current" \
    KOTLIN_HOME="$SDKMAN_DIR/candidates/kotlin/current" \
    PATH="$NVM_DIR/current/bin:$SDKMAN_DIR/candidates/java/current/bin:$SDKMAN_DIR/candidates/gradle/current/bin:$SDKMAN_DIR/candidates/kotlin/current/bin:$PATH"

# Copy pre-built CLI tools (rg, fd, ralphex) from tools stage
COPY --from=tools /usr/local/bin/rg /usr/local/bin/fd /usr/local/bin/ralphex /usr/local/bin/

# Copy pre-built Node.js via nvm from builder stage
COPY --from=nodejs /opt/nvm /opt/nvm

# Install uv (Python package manager)
COPY --from=ghcr.io/astral-sh/uv:0.9.28 /uv /uvx /usr/local/bin/

# Install AI CLI tools globally
RUN --mount=type=cache,target=/root/.npm \
    npm install -g @anthropic-ai/claude-code || true && \
    npm install -g @openai/codex || true && \
    npm install -g @google/gemini-cli || true

# Create cache directories that will be used at runtime
# Create cache directories under /.kodkod (mounted from ~/.kodkod on host)
RUN mkdir -p /.kodkod/m2 /.kodkod/gradle /.kodkod/npm /.kodkod/pip /.kodkod/uv \
             /.kodkod/config/claude /.kodkod/config/codex /.kodkod/config/gemini-cli && \
    chmod -R 755 /.kodkod && \
    chmod -R 777 /.kodkod/m2 /.kodkod/gradle /.kodkod/npm /.kodkod/pip /.kodkod/uv \
                  /.kodkod/config/claude /.kodkod/config/codex /.kodkod/config/gemini-cli

COPY tmux.conf /etc/tmux.conf

# Pre-create home directory for non-root users and allow passwd/group updates at runtime
RUN mkdir -p /home/kodkod && chmod 777 /home/kodkod && \
    chmod 666 /etc/passwd /etc/group

COPY .bashrc /home/kodkod/.bashrc
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["/bin/bash"]
