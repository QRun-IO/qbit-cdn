# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.1] - Unreleased

### Fixed
- Tag rule `{prefix.field}` placeholders now resolve the joined table from the `<prefix>Id` field's possible-value source, so prefixed tables (`cms_blog`) work; a missing joined table is logged instead of silently skipped.

## [0.1.0] - Unreleased

### Added
- First implementation; see README.md for the tables, permissions and configuration.
