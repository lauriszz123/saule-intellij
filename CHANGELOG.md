# Changelog

## 26.20

### Security

- A project's own `target/debug/saule-lsp` is no longer run just because the project was opened. The installed toolchain is used instead, and running a project's build is now an opt-in setting.
- Relative `PATH` entries are no longer searched.

### Added

- `~/.saule/bin` is searched after `PATH`, so an editor started from a launcher finds the toolchain without any setup.
- Highlighting and indentation for `finally`, and highlighting for `byte` and `bytes`.

### Changed

- Builds with Java 21, which the 2024.2 platform requires.
- Removed `userdata` and `thread`, which are not Saule types.

## Releasing

Release the Saule toolchain first, then push a `v<version>` tag here. The tag runs CI, which publishes to JetBrains Marketplace using the `JETBRAINS_TOKEN` repository secret. The first upload has to be made by hand on the Marketplace site.
