package com.github.pacogillet.omniplayer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class MusicPlayerWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = MusicPlayerToolWindow(toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(view.content, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class MusicPlayerToolWindow(parent: Disposable) {

    val content: JComponent

    private val log = Logger.getInstance(MusicPlayerToolWindow::class.java)

    private val artwork = ArtworkComponent()
    private val titleLabel = MarqueeLabel().apply { font = JBFont.label().asBold() }
    private val artistLabel = MarqueeLabel().apply {
        font = JBFont.label()
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private val progressBar = ProgressBarComponent()
    private val elapsedLabel = timeLabel()
    private val remainingLabel = timeLabel()

    private val prevButton = iconButton(AllIcons.Actions.Play_back, "Previous") {
        WindowsMediaController.previousTrack(); refreshSoon()
    }
    private val playPauseButton = iconButton(AllIcons.Actions.Resume, "Play/Pause") {
        WindowsMediaController.playPause(); refreshSoon()
    }
    private val nextButton = iconButton(AllIcons.Actions.Play_forward, "Next") {
        WindowsMediaController.nextTrack(); refreshSoon()
    }

    // POOLED_THREAD: getNowPlaying() blocks on the WinRT worker, so the poll must never run on the EDT.
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
    // EDT timer that advances the progress bar smoothly between WinRT polls.
    private val ticker = javax.swing.Timer(TICK_PERIOD_MS) { tickProgress() }

    // UI state shared between the poll thread (writes via invokeLater) and the EDT ticker.
    private var currentTrackKey: String? = null
    private var basePositionMs: Long = 0
    private var durationMs: Long = 0
    private var isPlaying: Boolean = false
    private var lastSyncNanos: Long = 0
    // True while the user is dragging the progress bar: freeze the ticker and incoming polls so the
    // scrub preview isn't overwritten until they release.
    private var scrubbing: Boolean = false

    init {
        content = buildUi()
        progressBar.onScrub = { previewMs ->
            // Live preview while dragging: show the target time without touching the player yet.
            scrubbing = true
            updateProgressUi(previewMs)
        }
        progressBar.onSeek = { targetMs ->
            // On release: optimistically reflect the new position, perform the real seek, then
            // re-sync shortly after so the bar tracks the player's actual state.
            scrubbing = false
            basePositionMs = targetMs
            lastSyncNanos = System.nanoTime()
            updateProgressUi(targetMs)
            WindowsMediaController.seekTo(targetMs)
            refreshSoon()
        }
        ticker.isRepeats = true
        ticker.start()
        Disposable { ticker.stop() }.also { com.intellij.openapi.util.Disposer.register(parent, it) }
        schedule(0)
    }

    private fun buildUi(): JComponent {
        val texts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            add(titleLabel)
            add(artistLabel)
        }

        // Elapsed time pinned left, remaining time pinned right.
        val times = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            add(elapsedLabel, BorderLayout.WEST)
            add(remainingLabel, BorderLayout.EAST)
        }
        val progress = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            add(progressBar)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(times)
        }

        // Horizontal BoxLayout (not FlowLayout) so the row never wraps. The buttons are compact and
        // sit close together; side glue keeps them centred while the window is wide.
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            add(Box.createHorizontalGlue())
            add(prevButton)
            add(Box.createHorizontalStrut(JBUI.scale(16)))
            add(playPauseButton)
            add(Box.createHorizontalStrut(JBUI.scale(16)))
            add(nextButton)
            add(Box.createHorizontalGlue())
        }

        // Give the time labels placeholder text so the progress row reports a correct preferred
        // height — an empty label can report zero height and clip the time row. The first poll
        // overwrites this immediately.
        elapsedLabel.text = "0:00"
        remainingLabel.text = "0:00"

        // Pin each row to its preferred height (max = pref) so the BoxLayout never stretches a row
        // when the tool window grows. Without this, the progress (with its time row) and the buttons
        // would each absorb part of the extra height and drift apart; pinning keeps the whole block
        // together and lets the trailing glue take ALL the surplus, holding the block at the top.
        for (row in listOf(texts, progress, buttons)) {
            row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        }

        // Vertical stack: title/artist, progress, buttons — kept together at the top; the trailing
        // glue absorbs any extra height when the tool window is taller than the content needs.
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
            add(texts)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(progress)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(buttons)
            add(Box.createVerticalGlue())
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(artwork, BorderLayout.WEST)
            add(center, BorderLayout.CENTER)
        }
    }

    // --- Polling (pooled thread) ---

    private fun refreshSoon() {
        pollAlarm.cancelAllRequests()
        schedule(REFRESH_AFTER_ACTION_MS)
    }

    private fun schedule(delayMs: Int) {
        if (!pollAlarm.isDisposed) pollAlarm.addRequest(::poll, delayMs)
    }

    private fun poll() {
        val nowPlaying = WindowsMediaController.getNowPlaying()
        // Only fetch artwork when the track changed — it is the most expensive call.
        val trackChanged = nowPlaying != null && nowPlaying.trackKey != currentTrackKey
        val artworkBytes = if (trackChanged) WindowsMediaController.getArtwork() else null
        ApplicationManager.getApplication().invokeLater {
            applyState(nowPlaying, trackChanged, artworkBytes)
        }
        schedule(REFRESH_INTERVAL_MS)
    }

    // --- EDT updates ---

    private fun applyState(nowPlaying: WindowsMediaController.NowPlaying?, trackChanged: Boolean, artworkBytes: ByteArray?) {
        if (nowPlaying == null) {
            currentTrackKey = null
            titleLabel.text = NO_MUSIC
            artistLabel.text = ""
            durationMs = 0
            basePositionMs = 0
            isPlaying = false
            progressBar.setProgress(0, 0)
            elapsedLabel.text = ""
            remainingLabel.text = ""
            artwork.setImage(null)
            playPauseButton.icon = AllIcons.Actions.Resume
            return
        }

        titleLabel.text = nowPlaying.title.ifBlank { "Unknown title" }
        artistLabel.text = listOfNotNull(
            nowPlaying.artist.ifBlank { null },
            nowPlaying.album.ifBlank { null },
        ).joinToString(" — ")

        isPlaying = nowPlaying.isPlaying
        playPauseButton.icon = if (isPlaying) AllIcons.Actions.Pause else AllIcons.Actions.Resume

        durationMs = nowPlaying.durationMs

        // While the user is scrubbing, leave the position/time display alone so the live preview
        // isn't overwritten by an incoming poll.
        if (!scrubbing) {
            basePositionMs = nowPlaying.positionMs
            lastSyncNanos = System.nanoTime()
            updateProgressUi(nowPlaying.positionMs)
        }

        if (trackChanged) {
            currentTrackKey = nowPlaying.trackKey
            artwork.setImage(decodeImage(artworkBytes))
        }
    }

    /** Advances the displayed position between polls so the bar moves smoothly while playing. */
    private fun tickProgress() {
        // Keep ticking even when the duration is unknown (some sources don't report it), so at least
        // the elapsed time advances. Only the active scrub preview should freeze the display.
        if (scrubbing) return
        var position = if (isPlaying) {
            val elapsed = (System.nanoTime() - lastSyncNanos) / 1_000_000
            basePositionMs + elapsed
        } else {
            basePositionMs
        }
        if (durationMs > 0) position = position.coerceAtMost(durationMs)
        updateProgressUi(position)
    }

    private fun updateProgressUi(positionMs: Long) {
        progressBar.setProgress(positionMs, durationMs)
        elapsedLabel.text = formatTime(positionMs)
        // When the duration is unknown (e.g. Apple Music in the browser), there's no meaningful
        // "remaining" value, so show a placeholder instead of a bogus -0:00.
        remainingLabel.text = if (durationMs > 0) "-" + formatTime(durationMs - positionMs) else "--:--"
    }

    private fun decodeImage(bytes: ByteArray?): BufferedImage? {
        if (bytes == null) return null
        return try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (t: Throwable) {
            log.debug("Failed to decode artwork", t)
            null
        }
    }

    // --- small UI helpers ---

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isFocusable = false
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty(4)
            // Pin a compact size (icon + small padding) so the L&F's default minimum width does not
            // bloat the row; this lets all three buttons stay visible at narrow widths.
            val pad = JBUI.scale(8)
            val side = maxOf(icon.iconWidth, icon.iconHeight) + pad
            val dim = Dimension(side, side)
            preferredSize = dim
            minimumSize = dim
            maximumSize = dim
            addActionListener { onClick() }
        }

    private fun timeLabel(): JBLabel = JBLabel("").apply {
        // JBFont.small() is the public API for a small label font (smallOrNewUiMedium is marked
        // internal by the platform and flagged by the Marketplace verifier).
        font = JBFont.small()
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }


    companion object {
        private const val NO_MUSIC = "No music playing"
        private const val REFRESH_INTERVAL_MS = 1000
        private const val REFRESH_AFTER_ACTION_MS = 150
        private const val TICK_PERIOD_MS = 250

        private fun formatTime(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}

/** Square cover-art view: paints the decoded image rounded, or a music-note placeholder. */
private class ArtworkComponent : JComponent() {

    private var image: BufferedImage? = null

    fun setImage(image: BufferedImage?) {
        this.image = image
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(SIZE), JBUI.scale(SIZE))
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val size = JBUI.scale(SIZE)
            val arc = JBUI.scale(10)
            val img = image
            if (img != null) {
                g2.clip = java.awt.geom.RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), arc.toFloat(), arc.toFloat())
                g2.drawImage(img, 0, 0, size, size, null)
            } else {
                g2.color = PLACEHOLDER_BG
                g2.fillRoundRect(0, 0, size, size, arc, arc)
                val icon = AllIcons.Nodes.PpLib
                icon.paintIcon(this, g2, (size - icon.iconWidth) / 2, (size - icon.iconHeight) / 2)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val SIZE = 64
        private val PLACEHOLDER_BG: Color = JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
    }
}
