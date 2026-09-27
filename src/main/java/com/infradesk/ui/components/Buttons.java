package com.infradesk.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import javax.swing.JButton;

/** Factory for the button styles in DESIGN.md. */
public final class Buttons {

    private Buttons() {
    }

    /** Accent background, white text. */
    public static JButton primary(String text, String icon) {
        JButton b = base(text, icon, Color.WHITE);
        b.putClientProperty(FlatClientProperties.STYLE,
                "background: $Component.accentColor; foreground: #FFFFFF; borderWidth: 0; font: +0 bold;"
                        + " hoverBackground: lighten($Component.accentColor,6%); pressedBackground: darken($Component.accentColor,6%)");
        return b;
    }

    /** Transparent background with a border. */
    public static JButton secondary(String text, String icon) {
        JButton b = base(text, icon, Theme.TEXT);
        b.putClientProperty(FlatClientProperties.STYLE,
                "background: null; borderColor: #4E5157; hoverBackground: #2B2D30");
        return b;
    }

    /** Destructive action: red border and text. */
    public static JButton danger(String text, String icon) {
        JButton b = base(text, icon, Theme.DANGER_TEXT);
        b.putClientProperty(FlatClientProperties.STYLE,
                "background: null; foreground: #F08A8A; borderColor: #6B3A3A; hoverBackground: #3A2426");
        return b;
    }

    /** Square icon-only button without border, for toolbars. */
    public static JButton icon(String icon, String tooltip, int size) {
        JButton b = new JButton(Icons.get(icon, 16));
        b.setToolTipText(tooltip);
        b.getAccessibleContext().setAccessibleName(tooltip);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setPreferredSize(new Dimension(size, size));
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static JButton base(String text, String icon, Color iconColor) {
        JButton b = new JButton(text);
        if (icon != null) {
            b.setIcon(Icons.get(icon, 15, iconColor));
            b.setIconTextGap(6);
        }
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Dimension pref = b.getPreferredSize();
        b.setPreferredSize(new Dimension(pref.width + 8, Theme.BUTTON_HEIGHT));
        return b;
    }
}
