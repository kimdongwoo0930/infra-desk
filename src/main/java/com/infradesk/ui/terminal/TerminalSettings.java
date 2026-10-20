package com.infradesk.ui.terminal;

import com.infradesk.ui.Theme;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import java.awt.Font;

/** JediTerm 모양: DESIGN.md의 터미널 색상과 고정폭 글꼴. */
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
     * SGR 시퀀스 이전에 출력되는 텍스트의 스타일. JediTerm에서는 deprecated지만 3.76은 아직 초기
     * 스타일에 이것을 쓰고, 기본값이 흰 바탕에 검은 글씨라서 색이 없는 배너 글자 뒤에 흰 막대가 보인다.
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
