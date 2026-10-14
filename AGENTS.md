# AGENTS.md

This file provides guidance to Codex when working with code in this repository.

## Project Overview

**QBit CDN**: QQQ QBit: put any CDN in front of a QQQ application (portable cache headers, change-driven invalidation, edge statistics, Admin -> CDN app). Design: [QRun-IO/qqq#1084](https://github.com/QRun-IO/qqq/pull/1084) (`docs/specs/2026-10-12-qbit-cdn-design.md`). Implementation in progress.

## Build Commands

```bash
./mvnw compile          # Compile (always use the wrapper: Maven 3.10 cannot read the parent POM)
./mvnw test             # Unit tests
./mvnw verify           # Unit tests + JaCoCo gate (thresholds in pom.xml)
```

## Architecture

**Entry point:** `CdnQBitProducer implements QBitMetaDataProducer<CdnQBitConfig>` in `com.kingsrook.qbits.cdn`. Component producers in this package (and below) are found by package scanning.

## Naming (owner rule)

- Tables are lowercase snake_case prefixed with the full qbit name: `cdn_<name>`. The QQQ table name and the backend (SQL) name are identical; possible-value sources take the table name.
- Permissions use the full qbit name: `cdn.<action>` (for example `cdn.view`, `cdn.admin`).
- Qbit data lives in standard, visible QQQ tables, secured by permissions and record security locks, never hidden.
- Processes run as table or record actions, never in the nav.

## Code Style

- 3-space indentation, opening braces on the next line, `return (x);`.
- Javadoc flower-box comments (`/*****` with `**` lines) on every class and method.
- Fluent setters (`withX()`).
- Wrapper types (`Integer`, `Boolean`) in config and entities.

## Dependencies

- Java 21.
- Parent `com.kingsrook:qbit-build-parent:2.0.0` (qqq 4.0.0). The default-active `qqq-snapshot` profile imports `qqq-bom-pom:4.1.0-RC.5` ahead of it, as in qbit-quick-search (ADR-0007). Remove that activation when the parent moves to 4.1.
- Tests: see pom.xml.

## Release

- CircleCI qqq-orb 0.6.8.
- `develop` publishes SNAPSHOTs.
- A `release/*` branch publishes RCs (the orb bumps the version).
- A `v*` tag publishes the release.
