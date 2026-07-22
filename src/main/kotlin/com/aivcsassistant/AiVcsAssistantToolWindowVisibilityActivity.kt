package com.aivcsassistant

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

class AiVcsAssistantToolWindowVisibilityActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AiVcsAssistantToolWindowVisibility.update(project)
        project.messageBus.connect().subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    AiVcsAssistantToolWindowVisibility.update(project)
                }

                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    AiVcsAssistantToolWindowVisibility.update(project)
                }
            },
        )
    }
}
