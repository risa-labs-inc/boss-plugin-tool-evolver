package ai.rever.boss.plugin.dynamic.toolevolver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Surface
import androidx.compose.material.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.FeatherIcons
import compose.icons.feathericons.Columns
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.PlusSquare
import compose.icons.feathericons.Server
import compose.icons.feathericons.Terminal
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardShape = RoundedCornerShape(8.dp)
private val Green = Color(0xFF5DBB63)
private val Amber = Color(0xFFF2A93B)

@Composable
internal fun StatusChip(enabled: Boolean, healthy: Boolean, loaded: Boolean) {
    val (label, color) = when {
        !loaded -> "unloaded" to MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
        !enabled -> "disabled" to MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
        !healthy -> "unhealthy" to MaterialTheme.colors.error
        else -> "healthy" to Green
    }
    Box(
        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------- Probe

@Composable
internal fun ProbeSection(viewModel: EvolverTabViewModel) {
    val target by viewModel.target.collectAsState()
    val snapshots by viewModel.snapshots.collectAsState()
    val sampling by viewModel.sampling.collectAsState()
    val leakSignals by viewModel.leakSignals.collectAsState()
    val instances by viewModel.instances.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            // ------------------------------------------------ tool info card
            Card(backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle("Tool")
                    target?.let { t ->
                        InfoLine("Version", t.version)
                        InfoLine("Jar", t.jarPath.ifBlank { "?" })
                        InfoLine("Open instances", instances.toString())
                        if (t.description.isNotBlank()) InfoLine("About", t.description)
                    } ?: Text(
                        "Plugin is not currently loaded.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.refreshTarget() }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { viewModel.reloadPlugin() }) {
                            Text("Reload plugin", fontSize = 11.sp)
                        }
                    }
                }
            }

            // --------------------------------------------------- memory card
            Card(backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Memory & leaks")
                        Spacer(Modifier.weight(1f))
                        if (sampling) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(onClick = { viewModel.requestGC() }, enabled = !sampling) {
                            Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Request GC", fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.sampleMemory() },
                            enabled = !sampling,
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                        ) {
                            Icon(Icons.Default.Memory, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sample memory", fontSize = 11.sp)
                        }
                    }
                    Text(
                        "Samples the JVM live-object histogram filtered to this plugin's classes (forces a GC).",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                    )
                    val latest = snapshots.lastOrNull()
                    if (latest == null) {
                        Text(
                            "No samples yet.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Metric("Footprint", MemoryProbe.humanBytes(latest.totalBytes))
                            Metric("Instances", latest.instanceCount.toString())
                            Metric("Classes", latest.classCount.toString())
                            Metric("Samples", snapshots.size.toString())
                            if (snapshots.size >= 2) {
                                val delta = latest.totalBytes - snapshots.first().totalBytes
                                Metric(
                                    "Δ since first",
                                    (if (delta >= 0) "+" else "-") + MemoryProbe.humanBytes(kotlin.math.abs(delta)),
                                    color = if (delta > 0) Amber else Green,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Top classes by retained bytes", fontSize = 10.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f))
                        latest.topClasses.forEach { c ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    MemoryProbe.humanBytes(c.bytes),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                                    modifier = Modifier.width(84.dp),
                                )
                                Text(
                                    "${c.instances}×",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                                    modifier = Modifier.width(70.dp),
                                )
                                Text(
                                    c.className,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (leakSignals.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        leakSignals.forEach { signal ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("⚠", fontSize = 12.sp, color = Amber)
                                Spacer(Modifier.width(6.dp))
                                Text(signal, fontSize = 11.sp, color = Amber)
                            }
                        }
                    } else if (snapshots.isNotEmpty()) {
                        Text("No leak signals observed.", fontSize = 11.sp, color = Green)
                    }
                }
            }

            // --------------------------------------------------- logs card
            // Rendered inline (not a fixed bottom pane) so it scrolls with the
            // rest of the Probe content as one page.
            LogsCard(viewModel)
    }
}

@Composable
private fun LogsCard(viewModel: EvolverTabViewModel) {
    val logs by viewModel.logs.collectAsState()
    val logQuery by viewModel.logQuery.collectAsState()
    val listState = rememberLazyListState()

    // Keep the newest line in view within the card's own scroll region.
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }

    Card(Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Logs (lines mentioning this tool)")
                Spacer(Modifier.weight(1f))
                Text(
                    "${logs.size} lines",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                )
                if (viewModel.canOpenInConsole) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.openInConsole() }) {
                        Text("Open in Console", fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = logQuery,
                onValueChange = viewModel::setLogQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                placeholder = { Text("Filter…", fontSize = 11.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search, null,
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(13.dp),
                    )
                },
            )
            Spacer(Modifier.height(6.dp))
            if (logs.isEmpty()) {
                Text(
                    "No matching log lines yet",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                // Fixed-height card region with its own scroll: the log list
                // stays bounded and scrolls independently of the page. A fixed
                // height is what lets a LazyColumn live inside the page's
                // verticalScroll (bounded constraint).
                LazyColumn(
                    Modifier.fillMaxWidth().height(260.dp),
                    state = listState,
                ) {
                    items(logs) { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                entry.formatTimestamp(),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.message,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- Evolve

@Composable
internal fun EvolveSection(viewModel: EvolverTabViewModel) {
    val repoPath by viewModel.repoPath.collectAsState()
    val task by viewModel.task.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val actionLog by viewModel.actionLog.collectAsState()
    val cloneUrl by viewModel.cloneUrl.collectAsState()
    val cloneParent by viewModel.cloneParent.collectAsState()
    val evolveMode by viewModel.evolveMode.collectAsState()
    val worktreeSlug by viewModel.worktreeSlug.collectAsState()
    val worktrees by viewModel.worktrees.collectAsState()
    // The slug the launcher will actually use — filter matching, the target
    // highlight, and all shown paths go through it so the UI never advertises
    // a dir/branch that differs from what launching creates or reuses.
    val slugPreview = remember(worktreeSlug) { viewModel.slugify(worktreeSlug) }
    val targetedWorktree = remember(worktrees, slugPreview) { worktrees.firstOrNull { it.slug == slugPreview } }
    val canEvolve by viewModel.canEvolve.collectAsState()
    val agentAvailability by viewModel.agentAvailability.collectAsState()
    val gitInstalled by viewModel.gitInstalled.collectAsState()
    val openPrs by viewModel.openPrs.collectAsState()
    val prsLoading by viewModel.prsLoading.collectAsState()
    val ghStatus by viewModel.ghStatus.collectAsState()
    val issueRepo by viewModel.issueRepo.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Source repo")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = repoPath ?: "",
                        onValueChange = viewModel::setRepoPath,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        placeholder = { Text("Path to the plugin's git checkout…", fontSize = 11.sp) },
                    )
                    OutlinedButton(onClick = { viewModel.browseRepo() }) {
                        Icon(Icons.Default.Folder, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Browse", fontSize = 11.sp)
                    }
                }

                // No local checkout found — offer to clone it (into the plugins
                // umbrella by default), the same acquisition step tool-creator does.
                if (repoPath == null) {
                    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
                    Text(
                        "No local checkout found — clone it to evolve:",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    )
                    OutlinedTextField(
                        value = cloneUrl,
                        onValueChange = viewModel::setCloneUrl,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        placeholder = { Text("git URL…", fontSize = 11.sp) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cloneParent,
                            onValueChange = viewModel::setCloneParent,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            placeholder = { Text("Clone into…", fontSize = 11.sp) },
                        )
                        OutlinedButton(onClick = { viewModel.browseCloneParent() }) {
                            Icon(Icons.Default.Folder, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Browse", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.cloneRepo() },
                            enabled = !busy && cloneUrl.isNotBlank() && gitInstalled,
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                        ) {
                            Text(if (gitInstalled) "Clone" else "git missing", fontSize = 11.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = task,
                    onValueChange = viewModel::setTask,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp),
                    placeholder = { Text("Optional: what should evolve? (passed to the agent)", fontSize = 11.sp) },
                    maxLines = 3,
                )
            }
        }

        // ---------------------------------------------------- evolution mode
        Card(backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Evolution mode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(
                        title = "Normal",
                        subtitle = "This checkout",
                        selected = evolveMode == EvolveMode.NORMAL,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.setEvolveMode(EvolveMode.NORMAL) }
                    ModeChip(
                        title = "Worktree",
                        subtitle = when {
                            !gitInstalled -> "Requires git (not installed)"
                            evolveMode == EvolveMode.WORKTREE && slugPreview.isEmpty() ->
                                "No worktree named — runs in this checkout"
                            else -> "Isolated branch — evolve in parallel"
                        },
                        selected = evolveMode == EvolveMode.WORKTREE,
                        enabled = gitInstalled,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.setEvolveMode(EvolveMode.WORKTREE) }
                }
                if (evolveMode == EvolveMode.WORKTREE) {
                    OutlinedTextField(
                        value = worktreeSlug,
                        onValueChange = viewModel::setWorktreeSlug,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp),
                        label = { Text("Optional: search worktrees or name a new one → .worktrees/<slug> on evolve/<slug>", fontSize = 10.sp) },
                        placeholder = { Text("e.g. dark-mode or fix-crash", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search, null,
                                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                    val filteredWorktrees = remember(worktrees, worktreeSlug, slugPreview) {
                        val query = worktreeSlug.trim()
                        when {
                            query.isEmpty() -> worktrees
                            // An exact target is picked (tapped or typed): keep the
                            // whole list visible so other worktrees stay reachable.
                            worktrees.any { it.slug == slugPreview } -> worktrees
                            // Match the raw text and its slugified form, so e.g.
                            // "Dark Mode" still finds the on-disk slug "dark-mode".
                            else -> worktrees.filter {
                                it.slug.contains(query, ignoreCase = true) ||
                                    it.branch.contains(query, ignoreCase = true) ||
                                    (slugPreview.isNotEmpty() && it.slug.contains(slugPreview, ignoreCase = true))
                            }
                        }
                    }
                    if (worktrees.isEmpty()) {
                        Text(
                            "No worktrees yet — leave blank to evolve this checkout, or name one to create it.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                        )
                    } else if (filteredWorktrees.isEmpty()) {
                        Text(
                            if (slugPreview.isEmpty())
                                "No matching worktrees — clear the name to evolve this checkout instead."
                            else "No matching worktrees — evolving creates .worktrees/$slugPreview on evolve/$slugPreview.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                        )
                    } else {
                        Text("Active worktrees (tap to target)", fontSize = 10.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f))
                        filteredWorktrees.forEach { wt ->
                            val active = wt.slug == slugPreview && slugPreview.isNotEmpty()
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { viewModel.setWorktreeSlug(wt.slug) }
                                    .background(
                                        if (active) Amber.copy(alpha = 0.12f) else MaterialTheme.colors.onSurface.copy(alpha = 0.04f),
                                        CardShape,
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(wt.slug, fontSize = 12.sp, color = MaterialTheme.colors.onSurface)
                                    Text(wt.branch, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
                                }
                                IconButton(onClick = { viewModel.removeWorktree(wt) }, enabled = !busy, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, "Remove worktree", tint = MaterialTheme.colors.error, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Evolve with…")
                if (!canEvolve) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Lock, null, tint = Amber, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Evolving is permission-gated. Ask an admin to grant you " +
                                "“${EvolverServices.EVOLVE_PERMISSION}”. Probe and Issue stay available.",
                            fontSize = 10.sp,
                            color = Amber,
                        )
                    }
                }
                Text(
                    when {
                        evolveMode == EvolveMode.WORKTREE && slugPreview.isEmpty() ->
                            "No worktree selected — evolves this checkout directly. Search or name one above to isolate the evolution."
                        evolveMode == EvolveMode.WORKTREE && targetedWorktree != null ->
                            "Uses existing worktree .worktrees/${targetedWorktree.slug} (${targetedWorktree.branch.ifBlank { "detached" }}), writes the evolve skill there, and opens the agent in a BossTerm tab."
                        evolveMode == EvolveMode.WORKTREE ->
                            "Creates .worktrees/$slugPreview on evolve/$slugPreview, writes the evolve skill there, and opens the agent in a BossTerm tab."
                        else -> "Writes the evolve skill (plugin context, hot-reload + PR workflow) into the repo and opens the agent in a BossTerm tab."
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CliAgent.entries.forEach { agent ->
                        val installed = agentAvailability[agent] == true
                        AgentButton(
                            agent = agent,
                            installed = installed,
                            // Gated on the CLI actually being installed — a missing
                            // binary can't be launched, so the button is disabled.
                            enabled = !busy && repoPath != null && canEvolve && installed,
                            onClick = { viewModel.launchEvolve(agent) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.rebuildAndReload() },
                        enabled = !busy && repoPath != null && canEvolve,
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rebuild & hot reload now", fontSize = 11.sp)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "The evolve agent normally does this itself via the evolver_hot_reload MCP tool.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colors.surface,
            shape = CardShape,
            elevation = 0.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                SectionTitle("Activity")
                Spacer(Modifier.height(6.dp))
                // Launched sessions first: each row jumps back to its terminal tab.
                val sessions by viewModel.sessions.collectAsState()
                if (sessions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sessions.asReversed().forEach { (session, open) ->
                            SessionRow(
                                session = session,
                                open = open,
                                onFocus = { viewModel.focusSessionTerminal(session) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
                    Spacer(Modifier.height(8.dp))
                }
                if (actionLog.isEmpty()) {
                    Text(
                        "Nothing yet — pick an agent above to start an evolution.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    )
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(actionLog.size) {
                        if (actionLog.isNotEmpty()) listState.scrollToItem(actionLog.size - 1)
                    }
                    LazyColumn(Modifier.fillMaxWidth().height(200.dp), state = listState) {
                        itemsIndexed(actionLog) { _, line ->
                            Text(
                                line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------ open PRs
        // Evolutions end in a PR (the evolve skill's ship-it step), so open
        // PRs on the target repo are surfaced right where they originate.
        GhListCard(
            title = "Open pull requests",
            entries = openPrs,
            loading = prsLoading,
            ghStatus = ghStatus,
            repoResolved = issueRepo != null,
            emptyText = "No open pull requests.",
            onRefresh = { viewModel.refreshPrs() },
            key = { it.number },
        ) { pr ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { viewModel.openPr(pr) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "#${pr.number}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    modifier = Modifier.width(52.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        pr.title,
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pr.branch,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/**
 * Shared chrome for the gh-backed list cards (open issues / open PRs): header
 * with count, spinner and refresh button, an empty state that names the actual
 * blocker (gh missing, gh unauthenticated, repo unresolved), and a bounded
 * lazy list of rows.
 */
@Composable
private fun <T> GhListCard(
    title: String,
    entries: List<T>,
    loading: Boolean,
    ghStatus: GhStatus,
    repoResolved: Boolean,
    emptyText: String,
    onRefresh: () -> Unit,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(title)
                Spacer(Modifier.width(6.dp))
                Text("${entries.size}", fontSize = 10.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f))
                Spacer(Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            if (entries.isEmpty()) {
                Text(
                    when {
                        ghStatus == GhStatus.NOT_INSTALLED -> "GitHub CLI (gh) is not installed."
                        ghStatus == GhStatus.NOT_AUTHENTICATED -> "Sign in with `gh auth login`, then refresh."
                        !repoResolved -> "No GitHub repo resolved for this plugin."
                        else -> emptyText
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(entries, key = key) { row(it) }
                }
            }
        }
    }
}

/**
 * One launched evolution session in the Activity card: agent + branch + start
 * time, with a button that re-focuses the session's terminal tab (disabled
 * once that tab has been closed).
 */
@Composable
private fun SessionRow(session: EvolveSession, open: Boolean, onFocus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = open, onClick = onFocus)
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f), CardShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FeatherIcons.Terminal, null,
            tint = if (open) Green else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.agent.displayName + (session.branch?.let { "  ·  $it" } ?: ""),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = if (open) 0.9f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "started " + remember(session.startedAtMs) {
                    SimpleDateFormat("HH:mm").format(Date(session.startedAtMs))
                } + if (open) "" else "  ·  terminal closed",
                fontSize = 9.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
            )
        }
        OutlinedButton(onClick = onFocus, enabled = open) {
            Text("Focus terminal", fontSize = 10.sp)
        }
    }
}

@Composable
private fun ModeChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        backgroundColor = if (selected) Amber.copy(alpha = 0.15f) else MaterialTheme.colors.background,
        shape = CardShape,
        elevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colors.onBackground.copy(alpha = 0.35f)
                    selected -> Amber
                    else -> MaterialTheme.colors.onBackground
                },
            )
            Text(subtitle, fontSize = 9.sp, color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.5f else 0.3f))
        }
    }
}

@Composable
private fun AgentButton(
    agent: CliAgent,
    installed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        backgroundColor = MaterialTheme.colors.background,
        shape = CardShape,
        elevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                agent.displayName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colors.onBackground
                else MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
            )
            Text(
                if (installed) "installed" else "not found",
                fontSize = 9.sp,
                color = if (installed) Green else Amber,
            )
        }
    }
}

// -------------------------------------------------------------------- helpers

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color? = null) {
    Column {
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = color ?: MaterialTheme.colors.onSurface,
        )
        Text(
            label,
            fontSize = 9.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
        )
    }
}

// --------------------------------------------------------------------- Issue

@Composable
internal fun IssueSection(viewModel: EvolverTabViewModel) {
    val title by viewModel.issueTitle.collectAsState()
    val body by viewModel.issueBody.collectAsState()
    val attach by viewModel.attachDiagnostics.collectAsState()
    val repo by viewModel.issueRepo.collectAsState()
    val busy by viewModel.issueBusy.collectAsState()
    val log by viewModel.issueLog.collectAsState()
    val ghStatus by viewModel.ghStatus.collectAsState()
    val openIssues by viewModel.openIssues.collectAsState()
    val issuesLoading by viewModel.issuesLoading.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("New GitHub issue")
                    Spacer(Modifier.weight(1f))
                    val ghWarning = when (ghStatus) {
                        GhStatus.NOT_INSTALLED -> "GitHub CLI (gh) not installed"
                        GhStatus.NOT_AUTHENTICATED -> "gh not authenticated — run gh auth login"
                        GhStatus.READY -> null
                    }
                    ghWarning?.let { Text(it, fontSize = 10.sp, color = Amber) }
                }
                OutlinedTextField(
                    value = repo ?: "",
                    onValueChange = viewModel::setIssueRepo,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    label = { Text("Repository (owner/repo)", fontSize = 10.sp) },
                    placeholder = { Text("risa-labs-inc/boss-plugin-…", fontSize = 11.sp) },
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = viewModel::setIssueTitle,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp),
                    label = { Text("Title", fontSize = 10.sp) },
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = viewModel::setIssueBody,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    textStyle = TextStyle(fontSize = 12.sp),
                    label = { Text("Description", fontSize = 10.sp) },
                    placeholder = { Text("What's the problem or request?", fontSize = 11.sp) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.setAttachDiagnostics(!attach) },
                    ) {
                        Checkbox(
                            checked = attach,
                            onCheckedChange = { viewModel.setAttachDiagnostics(it) },
                            colors = CheckboxDefaults.colors(checkedColor = Amber),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Attach diagnostics (memory, leaks, recent logs)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = { viewModel.createIssue() },
                        enabled = !busy && title.isNotBlank() && !repo.isNullOrBlank() && ghStatus == GhStatus.READY,
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                    ) {
                        Icon(Icons.Default.Send, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Create issue", fontSize = 11.sp)
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colors.surface,
            shape = CardShape,
            elevation = 0.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                SectionTitle("Activity")
                Spacer(Modifier.height(6.dp))
                if (log.isEmpty()) {
                    Text(
                        "Filed issues appear here.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    )
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }
                    LazyColumn(Modifier.fillMaxWidth().height(200.dp), state = listState) {
                        itemsIndexed(log) { _, line ->
                            Text(
                                line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (line.startsWith("Failed")) MaterialTheme.colors.error
                                else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }

        // ----------------------------------------------------- open issues
        GhListCard(
            title = "Open issues",
            entries = openIssues,
            loading = issuesLoading,
            ghStatus = ghStatus,
            repoResolved = repo != null,
            emptyText = "No open issues.",
            onRefresh = { viewModel.refreshIssues() },
            key = { it.number },
        ) { issue ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { viewModel.openIssue(issue) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "#${issue.number}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    issue.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// -------------------------------------------------- open-location chooser dialog

/**
 * Card-style "where to open" chooser, mirroring the host's terminal-link dialog:
 * icon + title + subtitle cards that act on click, plus a "Remember my choice"
 * checkbox and Cancel.
 */
@Composable
internal fun OpenLocationDialog(
    title: String,
    onChoose: (EvolveOpenLocation, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var remember by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Surface(
            color = MaterialTheme.colors.surface,
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp,
            modifier = Modifier.width(420.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colors.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Where should it open?",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(16.dp))
                LocationCard(
                    icon = FeatherIcons.ExternalLink,
                    title = "Existing Split",
                    subtitle = "Open in the other panel",
                    onClick = { onChoose(EvolveOpenLocation.EXISTING_SPLIT, remember) },
                )
                Spacer(Modifier.height(10.dp))
                LocationCard(
                    icon = FeatherIcons.Columns,
                    title = "New Vertical Split",
                    subtitle = "Open alongside the current tab",
                    onClick = { onChoose(EvolveOpenLocation.SPLIT_RIGHT, remember) },
                )
                Spacer(Modifier.height(10.dp))
                LocationCard(
                    icon = FeatherIcons.Server,
                    title = "New Horizontal Split",
                    subtitle = "Open below the current tab",
                    onClick = { onChoose(EvolveOpenLocation.SPLIT_DOWN, remember) },
                )
                Spacer(Modifier.height(10.dp))
                LocationCard(
                    icon = FeatherIcons.PlusSquare,
                    title = "New Tab",
                    subtitle = "Open in a new tab in the active panel",
                    onClick = { onChoose(EvolveOpenLocation.NEW_TAB, remember) },
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { remember = !remember },
                    ) {
                        Checkbox(
                            checked = remember,
                            onCheckedChange = { remember = it },
                            colors = CheckboxDefaults.colors(checkedColor = Amber),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Remember my choice",
                            fontSize = 13.sp,
                            color = MaterialTheme.colors.onSurface,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Amber, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colors.onSurface,
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}
