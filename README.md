# Tool Evolver

A [BOSS](https://github.com/risa-labs-inc/BossConsole) plugin that attaches a *evolver* to any
installed tool (plugin): watch how it behaves, then evolve it with an AI coding agent — without
leaving the app.

## Features

- **Sidebar panel** listing every installed tool, with a picker dialog ("Open Evolver…").
- **Per-tool evolver tab** in the main panel:
  - **Probe** — live memory footprint of that plugin (JVM class-histogram attribution), leak
    signals (leaked classloaders after unload, stale open instances, monotonic heap growth), and
    the host log stream filtered to the plugin.
  - **Evolve** — hand the plugin's source repo to **Claude Code**, **Codex**, **Gemini**, or
    **OpenCode** in a BossTerm tab, with a generated `evolve` skill carrying full plugin
    context. The agent builds, **hot-reloads the plugin into the running BOSS instance** (via the
    `evolver_hot_reload` MCP tool — `~/.boss/plugins` for installed hosts, `~/.boss_debug/plugins`
    for dev hosts), verifies, and finishes by opening a **PR**.
- **MCP tools** for agents: `evolver_list_tools`, `evolver_probe`, `evolver_open`,
  `evolver_evolve`, `evolver_hot_reload`.

## Build

```bash
./gradlew buildPluginJar   # → build/libs/boss-plugin-tool-evolver-<version>.jar
```

Copy the jar to `~/.boss/plugins/` (or `~/.boss_debug/plugins/` for a dev-mode host) and reload.
