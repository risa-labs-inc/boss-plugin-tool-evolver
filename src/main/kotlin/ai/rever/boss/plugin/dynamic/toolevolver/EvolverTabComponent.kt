package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

class EvolverTabComponent(
    ctx: ComponentContext,
    override val config: TabInfo,
    services: EvolverServices,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = EvolverTabType

    private val viewModel = EvolverTabViewModel(
        services = services,
        tabInfo = config as? EvolverTabInfo
            ?: EvolverTabInfo(targetPluginId = config.id.removePrefix("tool-evolver-"), targetDisplayName = config.title),
    )

    init {
        lifecycle.doOnDestroy { viewModel.dispose() }
    }

    @Composable
    override fun Content() {
        BossTheme {
            EvolverTabScreen(viewModel)
        }
    }
}

@Composable
private fun EvolverTabScreen(viewModel: EvolverTabViewModel) {
    val target by viewModel.target.collectAsState()
    val isLoaded by viewModel.isLoaded.collectAsState()
    val section by viewModel.section.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshTarget() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    target?.displayName ?: viewModel.tabInfo.targetDisplayName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    viewModel.tabInfo.targetPluginId +
                        (target?.let { "  ·  v${it.version}" } ?: "") +
                        (if (isLoaded) "" else "  ·  NOT LOADED"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(
                enabled = target?.isEnabled ?: false,
                healthy = target?.healthy ?: false,
                loaded = isLoaded,
            )
        }
        TabRow(
            selectedTabIndex = section.ordinal,
            backgroundColor = MaterialTheme.colors.background,
            contentColor = MaterialTheme.colors.primary,
        ) {
            EvolverSection.entries.forEach { s ->
                Tab(
                    selected = section == s,
                    onClick = { viewModel.setSection(s) },
                    text = { Text(s.label, fontSize = 12.sp) },
                )
            }
        }
        Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
        when (section) {
            EvolverSection.PROBE -> ProbeSection(viewModel)
            EvolverSection.EVOLVE -> EvolveSection(viewModel)
            EvolverSection.ISSUE -> IssueSection(viewModel)
        }
    }

    // Rendered at the tab level (not inside a section) so the chooser shows
    // regardless of which tab triggered it — evolve launch or issue-link open.
    val pendingOpen by viewModel.pendingOpen.collectAsState()
    pendingOpen?.let { pending ->
        OpenLocationDialog(
            title = pending.dialogTitle,
            onChoose = { loc, remember -> viewModel.onOpenLocationChosen(loc, remember) },
            onDismiss = { viewModel.dismissOpenDialog() },
        )
    }
}
