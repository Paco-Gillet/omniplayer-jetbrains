package com.github.pacogillet.omniplayer

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * A thin, rounded read-only progress bar mirroring the Apple Music mini player track. Shows the
 * fraction [positionMs] / [durationMs]; both are display-only (no seeking in this iteration).
 */
class ProgressBarComponent : JComponent() {

    private var positionMs: Long = 0
    private var durationMs: Long = 0

    fun setProgress(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        this.durationMs = durationMs
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(120), JBUI.scale(BAR_THICKNESS))
    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(40), JBUI.scale(BAR_THICKNESS))
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(BAR_THICKNESS))

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

            val fraction = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
            val filled = (w * fraction).toInt()
            if (filled > 0) {
                g2.color = FILL_COLOR
                g2.fillRoundRect(0, y, filled, h, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val BAR_THICKNESS = 4
        private val TRACK_COLOR = JBColor.namedColor("ProgressBar.trackColor", JBColor(0xD0D0D0, 0x4A4A4A))
        private val FILL_COLOR = JBColor.namedColor("ProgressBar.progressColor", JBColor(0x3574F0, 0x548AF7))
    }
}
