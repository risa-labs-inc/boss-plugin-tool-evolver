package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ToolEvolverPanelViewModel(private val services: EvolverServices) {

    private val _tools = MutableStateFlow<List<LoadedPluginInfo>>(emptyList())
    val tools: StateFlow<List<LoadedPluginInfo>> = _tools.asStateFlow()

    private val _showPicker = MutableStateFlow(false)
    val showPicker: StateFlow<Boolean> = _showPicker.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    fun refresh() {
        services.scope.launch(Dispatchers.IO) { _tools.value = services.listTools() }
    }

    fun openPicker() {
        refresh()
        _search.value = ""
        _showPicker.value = true
    }

    fun closePicker() {
        _showPicker.value = false
    }

    fun setSearch(value: String) {
        _search.value = value
    }

    fun openEvolver(info: LoadedPluginInfo) {
        if (services.openEvolverTab(info)) _showPicker.value = false
    }
}

class ToolEvolverPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    services: EvolverServices,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = ToolEvolverPanelViewModel(services)

    @Composable
    override fun Content() {
        BossTheme {
            ToolEvolverPanelContent(viewModel)
        }
    }
}

@Composable
private fun ToolEvolverPanelContent(viewModel: ToolEvolverPanelViewModel) {
    val tools by viewModel.tools.collectAsState()
    val showPicker by viewModel.showPicker.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(viewModel)
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
            if (tools.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No tools reported by the host",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(tools, key = { it.pluginId }) { tool ->
                        ToolRow(tool, onClick = { viewModel.openEvolver(tool) })
                    }
                }
            }
        }
        if (showPicker) {
            ToolPickerOverlay(viewModel)
        }
    }
}

@Composable
private fun PanelHeader(viewModel: ToolEvolverPanelViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Tool Evolver",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Refresh, "Refresh",
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.MoreVert, "More",
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(onClick = {
                    menuOpen = false
                    viewModel.openPicker()
                }) { Text("Open Evolver…", fontSize = 12.sp) }
                DropdownMenuItem(onClick = {
                    menuOpen = false
                    viewModel.refresh()
                }) { Text("Refresh tools", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: LoadedPluginInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).background(
                color = when {
                    !tool.isEnabled -> MaterialTheme.colors.onSurface.copy(alpha = 0.25f)
                    tool.healthy -> Color(0xFF5DBB63)
                    else -> MaterialTheme.colors.error
                },
                shape = RoundedCornerShape(50),
            )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tool.displayName,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "v${tool.version}" + if (tool.isEnabled) "" else " · disabled",
                fontSize = 10.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolPickerOverlay(viewModel: ToolEvolverPanelViewModel) {
    val tools by viewModel.tools.collectAsState()
    val search by viewModel.search.collectAsState()
    val filtered = remember(tools, search) {
        if (search.isBlank()) tools
        else tools.filter {
            it.displayName.contains(search, ignoreCase = true) ||
                it.pluginId.contains(search, ignoreCase = true)
        }
    }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = { viewModel.closePicker() }),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp)
                .clickable(enabled = false, onClick = {}),
            backgroundColor = MaterialTheme.colors.surface,
            shape = RoundedCornerShape(10.dp),
            elevation = 8.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Open Evolver for…",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colors.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = viewModel::setSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    placeholder = { Text("Search tools…", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search, null,
                            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp),
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.pluginId }) { tool ->
                        ToolRow(tool, onClick = { viewModel.openEvolver(tool) })
                    }
                }
            }
        }
    }
}
