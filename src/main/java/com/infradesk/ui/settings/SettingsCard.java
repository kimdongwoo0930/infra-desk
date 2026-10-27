package com.infradesk.ui.settings;

import com.infradesk.ui.Theme;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.BorderLayout;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/** 설정 행들을 한 테두리 안에 모은 카드 (디자인 v2). 행 사이에는 가는 선이 들어간다. */
public final class SettingsCard extends RoundedPanel {

    private final JPanel rows = new JPanel();
    private int count;

    public SettingsCard() {
        super(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        setAlignmentX(LEFT_ALIGNMENT);
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        add(rows, BorderLayout.CENTER);
    }

    /** 행(또는 다른 부품)을 아래에 붙인다. 앞에 이미 있으면 사이에 구분선을 넣는다. */
    public SettingsCard add(Component row) {
        if (count++ > 0) {
            JPanel line = new JPanel();
            line.setBackground(Theme.DIVIDER);
            line.setPreferredSize(new Dimension(0, 1));
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            rows.add(line);
        }
        if (row instanceof javax.swing.JComponent c) {
            c.setAlignmentX(LEFT_ALIGNMENT);
        }
        rows.add(row);
        return this;
    }

    /** 카드를 담는 열에서 세로로는 내용만큼만 차지하게 한다. */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
