package com.infradesk.ui.overview;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 개요 오른쪽 열의 "최근 활동" 카드 (디자인 v2). 준비 중: 활동 기록을 저장해 둘 곳이 아직 없다(이 PC에만, 30일,
 * 명령 출력·비밀값·공인 IP는 남기지 않는 저장소를 먼저 상의해야 한다). 만들어지면 이 카드에 최근 몇 건을 보여 준다.
 */
public final class RecentActivityCard extends RoundedPanel {

    public RecentActivityCard() {
        super(new BorderLayout(0, 10), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        setAlignmentX(LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        JLabel title = new JLabel("최근 활동");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        JButton all = ComingSoon.apply(Buttons.ghost("모두 보기"));
        // 비활성 버튼의 기본 배경(검정)이 아니라 글자 링크처럼 보이게 한다.
        all.putClientProperty(FlatClientProperties.STYLE, "background: #00000000; borderWidth: 0;"
                + " disabledBackground: #00000000; disabledText: #6B6E75");
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(title, BorderLayout.WEST);
        head.add(all, BorderLayout.EAST);
        JLabel body = new JLabel("활동 기록은 준비 중이에요");
        body.setForeground(Theme.TEXT_MUTED);
        body.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        add(head, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }
}
