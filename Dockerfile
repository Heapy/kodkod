# AI Agent Development Environment
# Amazon Linux 2023 + JDK, Gradle, Kotlin via SDKMAN

FROM amazonlinux:2023

ENV SDKMAN_DIR="/root/.sdkman" \
    RIPGREP_VERSION=15.1.0 \
    FD_VERSION=10.3.0 \
    NODE_VERSION=24 \
    RALPHEX_VERSION=0.6.0

# Install system dependencies
RUN dnf update -y && \
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
        && \
    dnf clean all

# Install SDKMAN and JDK, Gradle, Kotlin
RUN curl -s "https://get.sdkman.io" | bash && \
    bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && \
        sdk install java 21.0.10-librca && \
        sdk install java 17.0.18-librca && \
        sdk install java 25.0.2-librca && \
        sdk install gradle 9.3.1 && \
        sdk install kotlin 2.3.0"

ENV JAVA_HOME="$SDKMAN_DIR/candidates/java/current" \
    GRADLE_HOME="$SDKMAN_DIR/candidates/gradle/current" \
    KOTLIN_HOME="$SDKMAN_DIR/candidates/kotlin/current" \
    PATH="$SDKMAN_DIR/candidates/java/current/bin:$SDKMAN_DIR/candidates/gradle/current/bin:$SDKMAN_DIR/candidates/kotlin/current/bin:$PATH"

# Install ripgrep (multi-arch)
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then RG_ARCH="aarch64-unknown-linux-gnu"; else RG_ARCH="x86_64-unknown-linux-musl"; fi && \
    curl -L https://github.com/BurntSushi/ripgrep/releases/download/${RIPGREP_VERSION}/ripgrep-${RIPGREP_VERSION}-${RG_ARCH}.tar.gz -o /tmp/ripgrep.tar.gz && \
    tar -xzf /tmp/ripgrep.tar.gz -C /tmp && \
    cp /tmp/ripgrep-${RIPGREP_VERSION}-${RG_ARCH}/rg /usr/local/bin/ && \
    chmod +x /usr/local/bin/rg && \
    rm -rf /tmp/ripgrep*

# Install fd (multi-arch)
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then FD_ARCH="aarch64-unknown-linux-gnu"; else FD_ARCH="x86_64-unknown-linux-musl"; fi && \
    curl -L https://github.com/sharkdp/fd/releases/download/v${FD_VERSION}/fd-v${FD_VERSION}-${FD_ARCH}.tar.gz -o /tmp/fd.tar.gz && \
    tar -xzf /tmp/fd.tar.gz -C /tmp && \
    cp /tmp/fd-v${FD_VERSION}-${FD_ARCH}/fd /usr/local/bin/ && \
    chmod +x /usr/local/bin/fd && \
    rm -rf /tmp/fd*

# Install Node.js
RUN curl -fsSL https://rpm.nodesource.com/setup_${NODE_VERSION}.x | bash - && \
    dnf install -y nodejs && \
    dnf clean all

# Install uv (Python package manager)
COPY --from=ghcr.io/astral-sh/uv:0.9.28 /uv /uvx /usr/local/bin/

# Install AI CLI tools globally
RUN npm install -g @anthropic-ai/claude-code || true && \
    npm install -g @openai/codex || true && \
    npm install -g @google/gemini-cli || true

# Install ralphex from GitHub releases (multi-arch)
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then RX_ARCH="arm64"; else RX_ARCH="amd64"; fi && \
    curl -L https://github.com/umputun/ralphex/releases/download/v${RALPHEX_VERSION}/ralphex_${RALPHEX_VERSION}_linux_${RX_ARCH}.tar.gz -o /tmp/ralphex.tar.gz && \
    tar -xzf /tmp/ralphex.tar.gz -C /tmp && \
    mv /tmp/ralphex /usr/local/bin/ralphex && \
    chmod +x /usr/local/bin/ralphex && \
    rm -rf /tmp/ralphex*

# Create cache directories that will be used at runtime
# Create cache directories under /.kodkod (mounted from ~/.kodkod on host)
RUN mkdir -p /.kodkod/m2 /.kodkod/gradle /.kodkod/npm /.kodkod/pip /.kodkod/uv \
             /.kodkod/config/claude /.kodkod/config/codex /.kodkod/config/gemini-cli && \
    chmod -R 777 /.kodkod

# Set up bash aliases for AI CLI tools
RUN echo '# AI CLI tool aliases' >> /etc/bashrc && \
    echo 'alias claude="claude --dangerously-disable-sandbox"' >> /etc/bashrc && \
    echo 'alias codex="codex --no-safety"' >> /etc/bashrc

# Basic tmux configuration
RUN echo '# Basic tmux configuration' > /etc/tmux.conf && \
    echo 'set -g mouse on' >> /etc/tmux.conf && \
    echo 'set -g base-index 1' >> /etc/tmux.conf && \
    echo 'setw -g pane-base-index 1' >> /etc/tmux.conf && \
    echo 'set -g history-limit 10000' >> /etc/tmux.conf && \
    echo 'unbind C-b' >> /etc/tmux.conf && \
    echo 'set -g prefix C-a' >> /etc/tmux.conf && \
    echo 'bind C-a send-prefix' >> /etc/tmux.conf

WORKDIR /workspace

CMD ["/bin/bash"]
