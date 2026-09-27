package com.infradesk.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.util.SystemInfo;

import java.awt.Color;
import java.awt.Font;
import java.util.Map;

/** Palette from docs/design/DESIGN.md and FlatLaf setup. */
public final class Theme {

    public static final Color APP_BG = new Color(0x1E1F22);
    public static final Color PANEL_BG = new Color(0x2B2D30);
    public static final Color TERMINAL_BG = new Color(0x191A1C);
    public static final Color TERMINAL_BAR_BG = new Color(0x232427);
    public static final Color DIVIDER = new Color(0x393B40);
    public static final Color INPUT_BORDER = new Color(0x43454A);
    public static final Color SECONDARY_BUTTON_BORDER = new Color(0x4E5157);
    public static final Color DASHED_BORDER = new Color(0x55585E);
    public static final Color TEXT = new Color(0xDFE1E5);
    public static final Color TEXT_SECONDARY = new Color(0xB4B8BF);
    public static final Color TEXT_MUTED = new Color(0x8C8F94);
    public static final Color ACCENT = new Color(0x3B73E0);
    public static final Color SELECTION_BG = new Color(0x2E436E);
    public static final Color RUNNING_DOT = new Color(0x5FB865);
    public static final Color RUNNING_BADGE_BG = new Color(0x1F3A24);
    public static final Color RUNNING_BADGE_TEXT = new Color(0x7FD184);
    public static final Color DANGER_BORDER = new Color(0x6B3A3A);
    public static final Color DANGER_TEXT = new Color(0xF08A8A);
    public static final Color WARNING = new Color(0xE5C07B);

    public static final int ARC_CONTROL = 6;
    public static final int ARC_CARD = 8;
    public static final int ARC_DIALOG = 12;
    public static final int BUTTON_HEIGHT = 34;

    private Theme() {
    }

    /** Installs the look and feel. Must run before any Swing component is created. */
    public static void install() {
        FlatLaf.setGlobalExtraDefaults(Map.ofEntries(
                Map.entry("@background", hex(APP_BG)),
                Map.entry("@foreground", hex(TEXT)),
                Map.entry("@accentColor", hex(ACCENT)),
                Map.entry("@selectionBackground", hex(SELECTION_BG)),
                Map.entry("Component.borderColor", hex(INPUT_BORDER)),
                Map.entry("Component.disabledBorderColor", hex(DIVIDER)),
                Map.entry("Component.focusWidth", "0"),
                Map.entry("Component.innerFocusWidth", "1"),
                Map.entry("Component.arc", String.valueOf(ARC_CONTROL)),
                Map.entry("Button.arc", String.valueOf(ARC_CONTROL)),
                Map.entry("TextComponent.arc", String.valueOf(ARC_CONTROL)),
                Map.entry("Button.borderColor", hex(SECONDARY_BUTTON_BORDER)),
                Map.entry("Button.background", "@background"),
                Map.entry("Separator.foreground", hex(DIVIDER)),
                Map.entry("Label.disabledForeground", hex(TEXT_MUTED)),
                Map.entry("TitlePane.background", hex(PANEL_BG)),
                Map.entry("TitlePane.inactiveBackground", hex(PANEL_BG)),
                // FlatLaf on macOS otherwise shows every popup as a separate native window (for the
                // shadow and rounded border). Inside a modal dialog that window left the dialog
                // without keyboard/mouse input; plain Swing popups draw inside the window instead.
                Map.entry("Popup.dropShadowPainted", "false"),
                Map.entry("ScrollBar.width", "10"),
                Map.entry("ScrollBar.thumbArc", "999"),
                Map.entry("ScrollBar.thumbInsets", "2,2,2,2")));

        if (SystemInfo.isMacOS) {
            FlatMacDarkLaf.setup();
        } else {
            FlatDarculaLaf.setup();
        }
        warmUpGlyphCache();
        com.infradesk.ui.components.TextUndo.install();
        com.infradesk.ui.components.TextContextMenu.install();
    }

    /**
     * Works around a macOS JDK glyph-cache bug: if the first string drawn at a font size has a
     * '.' or quote right after Hangul, that punctuation is cached as an empty glyph and every later
     * '.' at that size renders as a space ("README.md" shows as "README md"). Drawing ASCII
     * punctuation first, for each size, style, scale and antialiasing mode the UI uses, fills the
     * cache with the correct glyphs.
     */
    private static void warmUpGlyphCache() {
        if (!SystemInfo.isMacOS) {
            return;
        }
        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) {
            return;
        }
        String sample = "README.md v1.2 'a' \"b\" : ; , ! ? ( ) [ ] - _ / @ # % & * + = ~ ...";
        Object[] aaModes = {
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_GASP,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT};
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            for (double scale : new double[] {1, 2}) {
                for (Object aa : aaModes) {
                    for (int size = 9; size <= 24; size++) {
                        for (int style : new int[] {java.awt.Font.PLAIN, java.awt.Font.BOLD}) {
                            java.awt.Graphics2D gg = (java.awt.Graphics2D) g.create();
                            gg.scale(scale, scale);
                            gg.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, aa);
                            gg.setFont(base.deriveFont(style, (float) size));
                            gg.drawString(sample, 0, 0);
                            gg.dispose();
                        }
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** Monospaced font for IPs, commands and terminal text. */
    public static Font monoFont(float size) {
        String family = SystemInfo.isMacOS ? "Menlo" : "Consolas";
        return new Font(family, Font.PLAIN, 1).deriveFont(size);
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }
}
