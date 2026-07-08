package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.TrendingUp

// EVOLVE first so it is the default/leftmost tab (ordinal 0 = selected on open);
// Issue second, Probe last.
enum class EvolverSection(val label: String) {
    EVOLVE("Evolve"),
    ISSUE("Issue"),
    PROBE("Probe"),
}

object EvolverTabType : TabTypeInfo {
    override val typeId = TabTypeId("tool-evolver")
    override val displayName = "Tool Evolver"
    override val icon = FeatherIcons.TrendingUp
}

/**
 * One evolver tab, bound to the tool (plugin) it probes/evolves. The id is
 * stable per target plugin so opening the evolver twice focuses the same tab.
 */
data class EvolverTabInfo(
    val targetPluginId: String,
    val targetDisplayName: String,
    val initialSection: EvolverSection = EvolverSection.EVOLVE,
    override val id: String = "tool-evolver-$targetPluginId",
    override val typeId: TabTypeId = EvolverTabType.typeId,
    override val title: String = targetDisplayName,
    override val icon: ImageVector = FeatherIcons.TrendingUp,
) : TabInfo
