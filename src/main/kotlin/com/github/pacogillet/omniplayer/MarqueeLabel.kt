package com.github.pacogillet.omniplayer

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

/**
 * A single-line, horizontally-centered text label that scrolls ("marquee") while the mouse hovers
 * over it, but only when the text is wider than the component. When it fits, the text stays centered
 * and never scrolls. Used for the track title / artist line, mirroring the Apple Music mini player.
 */
class MarqueeLabel : JComponent() {

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            offset = 0
            updateScrollingState()
            repaint()
        }

    private var offset = 0
    private var hovering = false

    // ~30 fps stepping; gap separates the end of the text from its wrapped-around head while scrolling.
    private val timer = Timer(SCROLL_PERIOD_MS) {
        val width = textWidth()
        if (width <= availableWidth()) {
            stopScrolling()
            return@Timer
        }
        offset = (offset + SCROLL_STEP) % (width + GAP)
        repaint()
    }

    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovering = true
                updateScrollingState()
            }

            override fun mouseExited(e: MouseEvent) {
                hovering = false
                offset = 0
                updateScrollingState()
                repaint()
            }
        })
    }

    private fun updateScrollingState() {
        val needsScroll = hovering && textWidth() > availableWidth()
        if (needsScroll && !timer.isRunning) timer.start()
        else if (!needsScroll && timer.isRunning) stopScrolling()
    }

    private fun stopScrolling() {
        if (timer.isRunning) timer.stop()
    }

    private fun textWidth(): Int = if (text.isEmpty()) 0 else getFontMetrics(font).stringWidth(text)
    private fun availableWidth(): Int = width - insets.left - insets.right

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val h = fm.height + insets.top + insets.bottom
        return Dimension(JBUI.scale(120), h)
    }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(40), preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = foreground
            val fm = g2.fontMetrics
            val baseline = insets.top + fm.ascent + (availableHeight() - fm.height) / 2
            val avail = availableWidth()
            val tw = textWidth()

            if (tw <= avail) {
                // Fits: draw centered, no scrolling.
                val x = insets.left + (avail - tw) / 2
                g2.drawString(text, x, baseline)
            } else {
                // Overflows: clip to the content area and draw the scrolling text (wrapped with a gap).
                g2.clipRect(insets.left, insets.top, avail, availableHeight())
                val start = insets.left - offset
                g2.drawString(text, start, baseline)
                g2.drawString(text, start + tw + GAP, baseline)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun availableHeight(): Int = height - insets.top - insets.bottom

    companion object {
        private const val SCROLL_PERIOD_MS = 33
        private const val SCROLL_STEP = 2
        private val GAP = JBUI.scale(48)
    }
}
