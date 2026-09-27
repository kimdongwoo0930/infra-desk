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
                Map.entry("ScrollBar.width", "10"),
                Map.entry("ScrollBar.thumbArc", "999"),
                Map.entry("ScrollBar.thumbInsets", "2,2,2,2")));

        if (SystemInfo.isMacOS) {
            FlatMacDarkLaf.setup();
        } else {
            FlatDarculaLaf.setup();
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
