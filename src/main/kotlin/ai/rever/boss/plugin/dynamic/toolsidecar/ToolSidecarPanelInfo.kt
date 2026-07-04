package ai.rever.boss.plugin.dynamic.toolsidecar

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity

object ToolSidecarPanelInfo : PanelInfo {
    override val id = PanelId("tool-sidecar", 31)
    override val displayName = "Tool Sidecar"
    override val icon = FeatherIcons.Activity
    override val defaultSlotPosition = left.bottom
}
