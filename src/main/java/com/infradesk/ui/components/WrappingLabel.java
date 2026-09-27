package com.infradesk.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * Read-only text that wraps at spaces to a fixed width. Used for status and hint text in dialogs;
 * an HTML {@code width} on a JLabel came out wider than asked on macOS and got clipped, and
 * JTextArea's own word wrap breaks inside Korean words ("서/버"). Breaks are computed here, only at
 * spaces, falling back to a character break for a single token longer than the line.
 */
public class WrappingLabel extends JTextArea {

    private final int wrapWidth;

    /** @param sizeDelta font size relative to the default label font, e.g. -1 */
    public WrappingLabel(String text, int wrapWidth, float sizeDelta, Color color) {
        this.wrapWidth = wrapWidth;
        setLineWrap(false);
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        setForeground(color);
        var base = UIManager.getFont("Label.font");
        setFont(base.deriveFont(base.getSize2D() + sizeDelta));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setText(text);
    }

    @Override
    public void setText(String t) {
        String text = t == null || t.isBlank() ? " " : t;
        super.setText(wrapWidth <= 0 || getFont() == null ? text : wrap(text, getFontMetrics(getFont()), wrapWidth));
        revalidate();
    }

    /** Greedy wrap at spaces; a token wider than the line is split by characters. */
    static String wrap(String text, java.awt.FontMetrics fm, int width) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(candidate) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    out.append(line).append('\n');
                    line.setLength(0);
                }
                for (char c : word.toCharArray()) {
                    if (fm.stringWidth(line.toString() + c) > width && !line.isEmpty()) {
                        out.append(line).append('\n');
                        line.setLength(0);
                    }
                    line.append(c);
                }
            }
            out.append(line);
        }
        return out.toString();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(wrapWidth, d.height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
