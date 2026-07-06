package ai.rever.boss.plugin.dynamic.toolsidecar

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
internal fun ProbeSection(viewModel: SidecarTabViewModel) {
    val target by viewModel.target.collectAsState()
    val snapshots by viewModel.snapshots.collectAsState()
    val sampling by viewModel.sampling.collectAsState()
    val leakSignals by viewModel.leakSignals.collectAsState()
    val instances by viewModel.instances.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f),
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
        }

        Spacer(Modifier.height(12.dp))
        LogsCard(viewModel, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun LogsCard(viewModel: SidecarTabViewModel, modifier: Modifier = Modifier) {
    val logs by viewModel.logs.collectAsState()
    val logQuery by viewModel.logQuery.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }

    Card(modifier, backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No matching log lines yet",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(logs) { entry ->
                        Row {
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
internal fun EvolveSection(viewModel: SidecarTabViewModel) {
    val repoPath by viewModel.repoPath.collectAsState()
    val task by viewModel.task.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val actionLog by viewModel.actionLog.collectAsState()
    val cloneUrl by viewModel.cloneUrl.collectAsState()
    val cloneParent by viewModel.cloneParent.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            enabled = !busy && cloneUrl.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                        ) {
                            Text("Clone", fontSize = 11.sp)
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

        Card(backgroundColor = MaterialTheme.colors.surface, shape = CardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Evolve with…")
                Text(
                    "Writes the sidecar-evolve skill (plugin context, hot-reload + PR workflow) into the repo and opens the agent in a BossTerm tab.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CliAgent.entries.forEach { agent ->
                        AgentButton(
                            agent = agent,
                            installed = viewModel.agentAvailability[agent] == true,
                            enabled = !busy && repoPath != null,
                            onClick = { viewModel.launchEvolve(agent) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.rebuildAndReload() },
                        enabled = !busy && repoPath != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rebuild & hot reload now", fontSize = 11.sp)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "The evolve agent normally does this itself via the sidecar_hot_reload MCP tool.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().weight(1f),
            backgroundColor = MaterialTheme.colors.surface,
            shape = CardShape,
            elevation = 0.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                SectionTitle("Activity")
                Spacer(Modifier.height(6.dp))
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
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
