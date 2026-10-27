package com.infradesk.ui.components;

import com.infradesk.core.ServerStatus;
import com.infradesk.ui.Theme;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;

/**
 * 알약 모양의 라벨. "실행 중", "확인 필요 1"처럼 짧은 상태나 개수를 보여 준다.
 *
 * <p>모습은 {@link Kind}(종류)로 정한다. 종류마다 배경색과 글자색이 {@link Theme}의 이름으로
 * 정해져 있어서, 이 클래스 안에 색 숫자를 직접 쓰지 않는다.
 */
public class StatusBadge extends JLabel {

    /**
     * 배지의 종류. 색만으로 뜻을 전하지 않도록(디자인 v2 규칙) 배지는 항상 글자와 함께 쓴다.
     */
    public enum Kind {
        /** 실행·성공 (초록). */
        SUCCESS(Theme.RUNNING_BADGE_BG, Theme.RUNNING_BADGE_TEXT),
        /** 확인 필요·경고, 상태가 바뀌는 중 (노랑). */
        WARNING(Theme.WARNING_BADGE_BG, Theme.WARNING_BADGE_TEXT),
        /** 위험·실패 (빨강). */
        DANGER(Theme.DANGER_BADGE_BG, Theme.DANGER_TEXT),
        /** 안내 (파랑). 옅은 파랑 배경 위에 강조 글자색. */
        INFO(Theme.ACCENT_SOFT_BG, Theme.ACCENT_TEXT),
        /** 중립: 정지됨, 탭 옆의 개수 등 (회색). */
        NEUTRAL(Theme.DIVIDER, Theme.TEXT);

        private final Color background;
        private final Color foreground;

        Kind(Color background, Color foreground) {
            this.background = background;
            this.foreground = foreground;
        }
    }

    /** 알약 배경. {@link #paintComponent}가 글자 뒤에 이 색으로 둥근 사각형을 먼저 칠한다. */
    private Color pill = Kind.NEUTRAL.background;
    private Kind kind = Kind.NEUTRAL;

    public StatusBadge() {
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        putClientProperty("FlatLaf.style", "font: -1");
    }

    /** 글자와 종류를 함께 정한다. 서버 상태가 아닌 배지(예: "확인 필요 1")는 이것을 쓴다. */
    public void setKind(Kind kind, String text) {
        this.kind = kind;
        this.pill = kind.background;
        setText(text);
        setForeground(kind.foreground);
        repaint();
    }

    /** 지금 종류. */
    public Kind kind() {
        return kind;
    }

    /**
     * 서버 상태를 배지로 보여 준다. 실행 중은 성공, 상태가 바뀌는 중(시작 중, 정지 중 …)은 경고,
     * 나머지(정지됨, 응답 없음 …)는 중립이다.
     */
    public void setStatus(ServerStatus status) {
        Kind k;
        if (status == ServerStatus.RUNNING) {
            k = Kind.SUCCESS;
        } else if (status.isTransitional()) {
            k = Kind.WARNING;
        } else {
            k = Kind.NEUTRAL;
        }
        setKind(k, status.label());
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, Math.max(d.height, 20));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(pill);
        // 호의 지름을 높이와 같게 주면 양 끝이 완전히 둥근 알약 모양이 된다.
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
