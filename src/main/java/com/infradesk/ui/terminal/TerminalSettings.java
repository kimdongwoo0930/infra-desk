package com.infradesk.ui.terminal;

import com.infradesk.ui.Theme;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import java.awt.Font;

/** JediTerm look: DESIGN.md terminal colors and a monospaced font. */
final class TerminalSettings extends DefaultSettingsProvider {

    private static final float FONT_SIZE = 13f;

    @Override
    public Font getTerminalFont() {
        return Theme.monoFont(FONT_SIZE);
    }

    @Override
    public float getTerminalFontSize() {
        return FONT_SIZE;
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return TerminalColor.rgb(0xC9, 0xCC, 0xD1);
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return TerminalColor.rgb(0x19, 0x1A, 0x1C);
    }

    /**
     * Style for text printed before any SGR sequence. Deprecated in JediTerm, but 3.76 still uses it
     * for the initial style and its default is black on white, which shows as white bars behind
     * uncolored banner text.
     */
    @Override
    @SuppressWarnings("deprecation")
    public TextStyle getDefaultStyle() {
        return new TextStyle(getDefaultForeground(), getDefaultBackground());
    }

    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(TerminalColor.rgb(0xFF, 0xFF, 0xFF), TerminalColor.rgb(0x2E, 0x43, 0x6E));
    }

    @Override
    public boolean audibleBell() {
        return false;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return 5000;
    }

    @Override
    public float getLineSpacing() {
        return 1.15f;
    }
}
