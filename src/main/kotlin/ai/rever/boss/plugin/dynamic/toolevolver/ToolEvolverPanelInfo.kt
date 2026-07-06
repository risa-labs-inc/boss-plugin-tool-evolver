package ai.rever.boss.plugin.dynamic.toolevolver

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity

object ToolEvolverPanelInfo : PanelInfo {
    override val id = PanelId("tool-evolver", 31)
    override val displayName = "Tool Evolver"
    override val icon = FeatherIcons.Activity
    override val defaultSlotPosition = left.bottom
}
