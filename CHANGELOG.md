# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- OCI standard labels in Dockerfile for image metadata
- CI smoke tests verifying key tools (java, gradle, node, rg, fd) after build
- CHANGELOG.md to track project changes

### Changed
- Tightened `/.kodkod` base directory permissions from 777 to 755; only cache subdirectories (m2, gradle, npm, pip, uv, config/*) retain 777 for write access

## [0.1.0] - 2026-02-02

### Added
- Initial Dockerfile based on Amazon Linux 2023
- JDK 17, 21, 25 via SDKMAN with Gradle 9.3.1 and Kotlin 2.3.0
- Node.js 24, ripgrep, fd, uv, ralphex
- AI CLI tools: claude-code, codex, gemini-cli
- `run.sh` launcher script and README
- GitHub Actions CI/CD workflow with multi-arch builds (amd64/arm64)
- Apache 2.0 license
