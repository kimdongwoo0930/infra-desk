package com.infradesk.ui.components;

import com.infradesk.core.ServerStatus;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.StatusBadge.Kind;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusBadgeTest {

    @Test
    void 서버_상태는_의도한_종류로_연결된다() {
        StatusBadge badge = new StatusBadge();

        badge.setStatus(ServerStatus.RUNNING);
        assertEquals(Kind.SUCCESS, badge.kind());
        assertEquals("실행 중", badge.getText());

        // 상태가 바뀌는 중이면 경고
        badge.setStatus(ServerStatus.STOPPING);
        assertEquals(Kind.WARNING, badge.kind());

        // 안정된 정지 상태는 중립
        badge.setStatus(ServerStatus.STOPPED);
        assertEquals(Kind.NEUTRAL, badge.kind());
    }

    @Test
    void 종류마다_글자색이_Theme_이름과_같다() {
        StatusBadge badge = new StatusBadge();

        badge.setKind(Kind.SUCCESS, "실행 중");
        assertEquals(Theme.RUNNING_BADGE_TEXT, badge.getForeground());
        badge.setKind(Kind.WARNING, "확인 필요 1");
        assertEquals(Theme.WARNING_BADGE_TEXT, badge.getForeground());
        badge.setKind(Kind.DANGER, "연결 실패");
        assertEquals(Theme.DANGER_TEXT, badge.getForeground());
        badge.setKind(Kind.INFO, "새 버전");
        assertEquals(Theme.ACCENT_TEXT, badge.getForeground());
        badge.setKind(Kind.NEUTRAL, "4");
        assertEquals(Theme.TEXT, badge.getForeground());
    }

    @Test
    void 알약_배경이_종류의_색으로_칠해진다() {
        StatusBadge badge = new StatusBadge();

        badge.setKind(Kind.WARNING, "확인 필요 1");
        assertEquals(Theme.WARNING_BADGE_BG.getRGB(), leftPaddingPixel(badge));

        badge.setKind(Kind.DANGER, "연결 실패");
        assertEquals(Theme.DANGER_BADGE_BG.getRGB(), leftPaddingPixel(badge));
    }

    /**
     * 배지를 이미지에 그려서, 글자가 없는 왼쪽 안쪽 여백(8px) 한가운데의 색을 읽는다.
     * 알약의 둥근 끝에서 안쪽으로 4px 들어간 곳이라 배경색만 칠해져 있다.
     */
    private static int leftPaddingPixel(StatusBadge badge) {
        Dimension size = badge.getPreferredSize();
        badge.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            badge.paint(g);
        } finally {
            g.dispose();
        }
        return image.getRGB(4, size.height / 2);
    }
}
