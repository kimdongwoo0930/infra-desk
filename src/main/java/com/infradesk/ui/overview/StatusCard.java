package com.infradesk.ui.overview;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Meter;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * "지금 상태"의 칸 하나 (디자인 v2): 이름, 큰 숫자, 그 아래 보조 글자, 맨 아래에 작은 선 그래프나 사용률 막대.
 * 누르면 모니터링 탭으로 간다(눌렀을 때 할 일은 쓰는 쪽이 정한다).
 */
final class StatusCard extends RoundedPanel {

    private static final Color NORMAL = Theme.PANEL_BG;
    private static final Color HOVER = new Color(0x2B2D31);

    private final JLabel value = new JLabel("—");
    private final JLabel sub = new JLabel(" ");
    private final Sparkline spark = new Sparkline();
    private final Meter bar = new Meter();
    private final boolean useBar;
    private Runnable onClick = () -> { };

    /** @param useBar true면 아래에 선 그래프 대신 사용률 막대(디스크)를 둔다 */
    StatusCard(String title, boolean useBar) {
        super(new BorderLayout(), NORMAL, Theme.DIVIDER, Theme.ARC_CARD);
        this.useBar = useBar;
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel name = new JLabel(title);
        name.setForeground(Theme.TEXT_MUTED);
        value.setFont(value.getFont().deriveFont(java.awt.Font.BOLD, 26f));
        sub.setForeground(Theme.TEXT_MUTED);
        sub.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        // 보조 글자가 길어도 카드를 넓히지 않고 "…"로 끝나게 한다.
        sub.setMinimumSize(new Dimension(0, sub.getPreferredSize().height));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        for (JLabel l : new JLabel[] {name, value, sub}) {
            l.setAlignmentX(LEFT_ALIGNMENT);
        }
        text.add(name);
        text.add(Box.createVerticalStrut(6));
        text.add(value);
        text.add(Box.createVerticalStrut(2));
        text.add(sub);
        add(text, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        bottom.add(useBar ? bar : spark, BorderLayout.SOUTH);
        add(bottom, BorderLayout.CENTER);

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                setBackground(HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setBackground(NORMAL);
            }
        };
        addMouseListener(click);
        for (java.awt.Component c : new java.awt.Component[] {text, name, value, sub, bottom, spark, bar}) {
            c.addMouseListener(click);
        }
    }

    void onClick(Runnable listener) {
        this.onClick = listener;
    }

    /** 숫자와 보조 글자를 바꾼다. */
    void setText(String valueText, String subText) {
        value.setText(valueText);
        sub.setText(subText == null || subText.isEmpty() ? " " : subText);
        sub.setToolTipText(subText);
    }

    /** 아래 선 그래프를 바꾼다(막대 카드에서는 쓰지 않는다). */
    void setSpark(List<Double> values, double max, Color color) {
        if (!useBar) {
            spark.set(values, max, color);
        }
    }

    /** 아래 사용률 막대를 바꾼다(그래프 카드에서는 쓰지 않는다). */
    void setPercent(double percent) {
        if (useBar) {
            bar.setPercent(percent);
        }
    }

    String valueText() {
        return value.getText();
    }

    String subText() {
        return sub.getText();
    }
}
