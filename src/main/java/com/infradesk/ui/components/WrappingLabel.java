package com.infradesk.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * 고정 너비에서 공백 기준으로 줄바꿈하는 읽기 전용 텍스트. 다이얼로그의 상태와 안내 문구에 쓴다.
 * JLabel에 HTML {@code width}를 주면 macOS에서 요청보다 넓게 나와 잘렸고, JTextArea 자체의
 * 줄바꿈은 한글 단어 안에서 끊어진다("서/버"). 그래서 여기서 줄바꿈 위치를 계산하며, 공백에서만
 * 끊고 한 토큰이 한 줄보다 길면 글자 단위로 끊는다.
 */
public class WrappingLabel extends JTextArea {

    private final int wrapWidth;

    /** @param sizeDelta 기본 라벨 글꼴 대비 글꼴 크기. 예: -1 */
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

    /** 공백에서 탐욕적으로 줄바꿈한다. 한 줄보다 넓은 토큰은 글자 단위로 쪼갠다. */
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
