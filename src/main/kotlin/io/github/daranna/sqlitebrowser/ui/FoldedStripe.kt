package io.github.daranna.sqlitebrowser.ui

import com.intellij.ide.ui.UISettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A side panel folded to the left edge, like a tool window stripe: its icon and title, written top to bottom. A click
 * calls [onOpen]. A label says what is folded there, which an icon alone on a strip this narrow doesn't.
 */
internal class FoldedStripe(private val icon: Icon, private val title: String, private val onOpen: () -> Unit) : JComponent() {
    private var hovered = false

    // Scaled when read, so they follow the IDE's zoom.
    private val pad get() = JBUI.scale(4)
    private val gap get() = JBUI.scale(6)

    init {
        font = JBUI.Fonts.smallFont()
        toolTipText = "Show ${title.lowercase()}"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.customLineRight(JBColor.border())
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onOpen()

            override fun mouseEntered(e: MouseEvent) = setHovered(true)

            override fun mouseExited(e: MouseEvent) = setHovered(false)
        })
    }

    private fun setHovered(value: Boolean) {
        hovered = value
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        val width = maxOf(icon.iconWidth, metrics.height) + 2 * pad
        return Dimension(width, pad + icon.iconHeight + gap + metrics.stringWidth(title) + pad)
    }

    override fun getMinimumSize(): Dimension = Dimension(preferredSize.width, 0)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            if (hovered) {
                g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                g2.fillRect(0, 0, width - 1, height)
            }
            icon.paintIcon(this, g2, (width - 1 - icon.iconWidth) / 2, pad)
            UISettings.setupAntialiasing(g2)
            g2.font = font
            g2.color = if (hovered) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            val metrics = g2.fontMetrics
            // Turned clockwise, the ascent points right; centers the line across the stripe.
            g2.translate((width - 1 - metrics.height) / 2 + metrics.descent, pad + icon.iconHeight + gap)
            g2.rotate(Math.PI / 2)
            g2.drawString(title, 0, 0)
        } finally {
            g2.dispose()
        }
    }
}
