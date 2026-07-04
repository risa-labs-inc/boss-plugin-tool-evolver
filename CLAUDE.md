# CLAUDE.md

## Project Overview

**Tool Sidecar** (`ai.rever.boss.plugin.dynamic.toolsidecar`) is a dynamic plugin for the BOSS desktop application.

Probe and evolve installed tools (plugins): per-plugin memory footprint + leak signals + logs, and AI-driven evolution with Claude Code, Codex, Gemini, or OpenCode — including live hot reload and PR creation.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.toolsidecar`
- **Main Class**: `ai.rever.boss.plugin.dynamic.toolsidecar.ToolSidecarDynamicPlugin`
- **API Version**: 1.0.55

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build             # Full build
./gradlew processResources  # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` (installed host) or `~/.boss_debug/plugins/` (dev-mode host) for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.toolsidecar)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest (type: mixed — panel + tab)
src/main/resources/templates/evolve-skill-body.md   → Skill body written into evolved plugin repos
build.gradle.kts   → Build config + version (single source of truth)
```

### What this plugin does

- **Sidebar panel** (`ToolSidecarPanelInfo`, left.bottom): lists installed tools via
  `PluginLoaderDelegate.getLoadedPlugins()` (obtained through `context.getPluginAPI`); its ⋮ menu
  and the "Open Sidecar…" picker overlay select a tool.
- **Sidecar tab** (`SidecarTabType`, opened via `splitViewOperations.openTab(SidecarTabInfo(...))`,
  stable id per target plugin) with two sections:
  - **Probe** — memory footprint of the target plugin sampled from the in-process
    `DiagnosticCommand` MBean class histogram (`MemoryProbe`, filtered by the plugin's mainClass
    package; forces a GC per sample), leak heuristics (`MemoryProbe.leakSignals`: unloaded-but-
    resident objects, >1 running instances, monotonic growth), and host stdout/stderr log lines
    filtered to the plugin (`logDataProvider` + keyword match).
  - **Evolve** — locates the plugin's source repo (workspace scan in `EvolveLauncher`, manual
    override via directory picker), writes the `sidecar-evolve` skill in all four CLI formats
    (`.claude/skills/`, `.codex/skills/`, `.gemini/commands/`, `.opencode/command/`), and opens a
    BossTerm tab (`TerminalTabInfo(initialCommand, workingDirectory)`) running the chosen CLI.
    Also offers "Rebuild & hot reload now" (`HotReloader.rebuildAndReload`: `./gradlew
    buildPluginJar` via ProcessBuilder, then live reload).
- **Hot reload** (`HotReloader`): copies a built jar into the RUNNING host's plugins dir
  (`PluginLoaderDelegate.getPluginsDirectory()` — `~/.boss/plugins` installed, `~/.boss_debug/plugins`
  dev mode), deletes stale jars of the same plugin, `unloadPlugin` + `loadPlugin`. No restart.
- **MCP tools** (`ToolSidecarMcpToolProvider`): `sidecar_list_tools`, `sidecar_probe`,
  `sidecar_open`, `sidecar_evolve`, `sidecar_hot_reload`. The evolve skill instructs the agent to
  call `sidecar_hot_reload` after each build and to finish by opening a PR (`gh pr create` from an
  `evolve/<topic>` branch — never pushing `main`, which would trigger a store release).

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` / `TabComponentWithUI` with `@Composable Content()` wrapped in `BossTheme`
- State: ViewModel pattern with `StateFlow` (`SidecarServices` is the shared hub)
- Providers from `PluginContext`: `splitViewOperations`, `logDataProvider`, `performanceDataProvider`,
  `notificationProvider`, `directoryPickerProvider`; `PluginLoaderDelegate` via `getPluginAPI`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` — only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully — show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.
