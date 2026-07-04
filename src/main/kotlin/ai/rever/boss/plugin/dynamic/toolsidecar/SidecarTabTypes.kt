package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity

enum class SidecarSection { PROBE, EVOLVE }

object SidecarTabType : TabTypeInfo {
    override val typeId = TabTypeId("tool-sidecar")
    override val displayName = "Tool Sidecar"
    override val icon = FeatherIcons.Activity
}

/**
 * One sidecar tab, bound to the tool (plugin) it probes/evolves. The id is
 * stable per target plugin so opening the sidecar twice focuses the same tab.
 */
data class SidecarTabInfo(
    val targetPluginId: String,
    val targetDisplayName: String,
    val initialSection: SidecarSection = SidecarSection.PROBE,
    override val id: String = "tool-sidecar-$targetPluginId",
    override val typeId: TabTypeId = SidecarTabType.typeId,
    override val title: String = targetDisplayName,
    override val icon: ImageVector = FeatherIcons.Activity,
) : TabInfo
