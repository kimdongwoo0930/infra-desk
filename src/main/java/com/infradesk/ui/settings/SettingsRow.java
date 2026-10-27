package com.infradesk.ui.settings;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.ComingSoon;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 설정 한 줄 (디자인 v2): 왼쪽에 굵은 제목과 흐린 설명, 오른쪽에 컨트롤(스위치, 선택 목록, 버튼, 값 글자).
 *
 * <p>아직 동작하지 않는 설정은 {@link #comingSoon()}으로 자리만 만든다: 제목 옆에 "준비 중" 배지가 붙고 컨트롤은
 * 비활성이라 눌러도 아무 일도 하지 않는다(기능이 생기면 이 호출만 지운다).
 */
public final class SettingsRow extends JPanel {

    private final JLabel title = new JLabel();
    private final JLabel description = new JLabel(" ");
    private final JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JComponent control;

    public SettingsRow(String titleText, String descriptionText, JComponent control) {
        super(new BorderLayout(24, 0));
        this.control = control;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        title.setText(titleText);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        titleLine.setOpaque(false);
        titleLine.add(title);
        description.setForeground(Theme.TEXT_MUTED);
        description.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        setDescription(descriptionText);
        // 설명이 길면 컨트롤을 밀어내지 않고 "…"로 끝나게 한다.
        description.setMinimumSize(new Dimension(0, description.getPreferredSize().height));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        titleLine.setAlignmentX(LEFT_ALIGNMENT);
        description.setAlignmentX(LEFT_ALIGNMENT);
        text.add(titleLine);
        text.add(javax.swing.Box.createVerticalStrut(4));
        text.add(description);
        add(text, BorderLayout.CENTER);

        if (control != null) {
            JPanel east = new JPanel(new GridBagLayout());
            east.setOpaque(false);
            east.add(control);
            add(east, BorderLayout.EAST);
        }
    }

    /** 아직 동작하지 않는 설정: 제목 옆에 "준비 중" 배지, 컨트롤은 비활성. */
    public SettingsRow comingSoon() {
        titleLine.add(javax.swing.Box.createHorizontalStrut(8));   // 제목 왼쪽 끝은 설명과 맞추고 배지만 띄운다
        titleLine.add(ComingSoon.badge());
        if (control != null) {
            ComingSoon.apply(control);
        }
        return this;
    }

    public void setDescription(String text) {
        description.setText(text == null || text.isEmpty() ? " " : text);
        description.setToolTipText(text);
    }

    public String titleText() {
        return title.getText();
    }

    public String descriptionText() {
        return description.getText();
    }

    public JComponent control() {
        return control;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
