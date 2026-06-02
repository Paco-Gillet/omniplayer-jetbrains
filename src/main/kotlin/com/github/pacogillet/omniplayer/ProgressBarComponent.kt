package com.github.pacogillet.omniplayer

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * A thin, rounded progress bar mirroring the Apple Music mini player track. Shows the fraction
 * [positionMs] / [durationMs] and lets the user seek: clicking or dragging anywhere on the bar moves
 * the playhead, and [onSeek] is invoked with the target position (ms) on release. While dragging, the
 * bar previews the new position locally so it feels responsive even before the player catches up.
 */
class ProgressBarComponent : JComponent() {

    /** Called with the requested position in milliseconds when the user finishes a click/drag. */
    var onSeek: ((Long) -> Unit)? = null

    /** Called continuously while the user presses/drags, with the previewed position (ms). */
    var onScrub: ((Long) -> Unit)? = null

    private var positionMs: Long = 0
    private var durationMs: Long = 0

    private var dragging = false
    private var dragFraction = 0.0

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (durationMs <= 0) return
                dragging = true
                dragFraction = fractionAt(e.x)
                onScrub?.invoke((dragFraction * durationMs).toLong())
                repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!dragging) return
                dragFraction = fractionAt(e.x)
                onScrub?.invoke((dragFraction * durationMs).toLong())
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!dragging) return
                dragging = false
                if (durationMs > 0) onSeek?.invoke((dragFraction * durationMs).toLong())
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    fun setProgress(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        this.durationMs = durationMs
        // Don't fight the user's drag preview with incoming polls.
        if (!dragging) repaint()
    }

    private fun fractionAt(x: Int): Double = (x.toDouble() / width.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(120), JBUI.scale(HIT_HEIGHT))
    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(40), JBUI.scale(HIT_HEIGHT))
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(HIT_HEIGHT))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = JBUI.scale(BAR_THICKNESS)
            val y = (height - h) / 2
            val arc = h

            g2.color = TRACK_COLOR
            g2.fillRoundRect(0, y, w, h, arc, arc)

            val fraction = when {
                dragging -> dragFraction
                durationMs > 0 -> (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            val filled = (w * fraction).toInt()
            if (filled > 0) {
                g2.color = FILL_COLOR
                g2.fillRoundRect(0, y, filled, h, arc, arc)
            }

            // Draw the draggable knob while interacting, like a scrubber.
            if (dragging) {
                val knobR = JBUI.scale(KNOB_RADIUS)
                val cx = filled.coerceIn(knobR, w - knobR)
                val cy = height / 2
                g2.color = FILL_COLOR
                g2.fillOval(cx - knobR, cy - knobR, knobR * 2, knobR * 2)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val BAR_THICKNESS = 4
        // Taller hit area than the visible bar so it is easy to grab with the mouse.
        private const val HIT_HEIGHT = 12
        private const val KNOB_RADIUS = 5
        private val TRACK_COLOR = JBColor.namedColor("ProgressBar.trackColor", JBColor(0xD0D0D0, 0x4A4A4A))
        private val FILL_COLOR = JBColor.namedColor("ProgressBar.progressColor", JBColor(0x3574F0, 0x548AF7))
    }
}
