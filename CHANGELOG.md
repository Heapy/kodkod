# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.2.0] - 2026-06-03

### Added
- Kotlin 2.4.0 application (Gradle 9.5.1) talking to the Docker Engine API directly
  over the unix socket, with a single runtime dependency (kotlinx-serialization-json).
- Autoheal loop: restarts `unhealthy` containers (labels `kodkod.autoheal.enable`,
  `kodkod.stop.timeout`).
- Update loop: pulls a container's image tag and recreates it — preserving config, env,
  labels and networks — when the image changes (label `kodkod.update.enable`).
- Lightweight multi-stage Dockerfile on BellSoft Liberica (JDK build → JRE Alpine runtime).
