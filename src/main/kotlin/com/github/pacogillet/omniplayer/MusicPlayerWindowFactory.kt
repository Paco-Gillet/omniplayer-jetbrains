package com.github.pacogillet.omniplayer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class MusicPlayerWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = MusicPlayerToolWindow(toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(view.content, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class MusicPlayerToolWindow(parent: Disposable) {

    val content = JPanel(BorderLayout())

    private val statusLabel = JLabel(NO_MUSIC)
    private val playPauseButton = JButton(PLAY_PAUSE)
    private val prevButton = JButton("Previous")
    private val nextButton = JButton("Next")

    // POOLED_THREAD: getNowPlaying() blocks on the WinRT worker, so the poll must never run on the EDT.
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)

    init {
        playPauseButton.addActionListener { WindowsMediaController.playPause(); refreshSoon() }
        prevButton.addActionListener { WindowsMediaController.previousTrack(); refreshSoon() }
        nextButton.addActionListener { WindowsMediaController.nextTrack(); refreshSoon() }

        val buttons = JPanel().apply {
            add(prevButton)
            add(playPauseButton)
            add(nextButton)
        }
        content.add(statusLabel, BorderLayout.NORTH)
        content.add(buttons, BorderLayout.CENTER)

        schedule(0)
    }

    /** Refresh quickly after a user action so the label/button reflect the new state. */
    private fun refreshSoon() {
        alarm.cancelAllRequests()
        schedule(REFRESH_AFTER_ACTION_MS)
    }

    private fun schedule(delayMs: Int) {
        if (!alarm.isDisposed) alarm.addRequest(::refresh, delayMs)
    }

    /** Runs on a pooled thread: query WinRT, push the result to the EDT, then reschedule. */
    private fun refresh() {
        val nowPlaying = WindowsMediaController.getNowPlaying()
        ApplicationManager.getApplication().invokeLater { applyState(nowPlaying) }
        schedule(REFRESH_INTERVAL_MS)
    }

    private fun applyState(nowPlaying: WindowsMediaController.NowPlaying?) {
        if (nowPlaying == null) {
            statusLabel.text = NO_MUSIC
            playPauseButton.text = PLAY_PAUSE
            return
        }
        statusLabel.text = when {
            nowPlaying.title.isBlank() && nowPlaying.artist.isBlank() -> "Playing…"
            nowPlaying.artist.isBlank() -> nowPlaying.title
            nowPlaying.title.isBlank() -> nowPlaying.artist
            else -> "${nowPlaying.artist} — ${nowPlaying.title}"
        }
        playPauseButton.text = if (nowPlaying.isPlaying) "Pause" else "Play"
    }

    companion object {
        private const val NO_MUSIC = "No music playing"
        private const val PLAY_PAUSE = "Play/Pause"
        private const val REFRESH_INTERVAL_MS = 750
        private const val REFRESH_AFTER_ACTION_MS = 150
    }
}
