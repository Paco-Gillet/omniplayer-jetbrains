package com.github.pacogillet.omniplayer

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout

class MusicPlayerWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MusicPlayerToolWindow()
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(myToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class MusicPlayerToolWindow {
    private val myToolWindowContent = JPanel(BorderLayout())

    init {
        val label = JLabel("No music playing")
        val playPauseButton = JButton("Play/Pause")
        val prevButton = JButton("Previous")
        val nextButton = JButton("Next")

        playPauseButton.addActionListener { WindowsMediaController.playPause() }
        prevButton.addActionListener { WindowsMediaController.previousTrack() }
        nextButton.addActionListener { WindowsMediaController.nextTrack() }
        
        val buttonPanel = JPanel()
        buttonPanel.add(prevButton)
        buttonPanel.add(playPauseButton)
        buttonPanel.add(nextButton)

        myToolWindowContent.add(label, BorderLayout.NORTH)
        myToolWindowContent.add(buttonPanel, BorderLayout.CENTER)
    }

    fun getContent(): JPanel {
        return myToolWindowContent
    }
}
