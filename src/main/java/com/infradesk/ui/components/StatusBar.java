package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 창 맨 아래의 상태 막대 (디자인 v2). 높이 26, 위쪽에 1px 구분선, 글자 11.5의 흐린 색.
 * 왼쪽에는 지금 상태("실시간 꺼짐" 등), 오른쪽에는 설정·버전("상태 30초 · 메트릭 1분마다", "v1.1.9")을 놓는다.
 *
 * <p>이 부품은 "어떤 항목을 보여 줄지" 모른다. 항목은 만든 쪽이 {@link #addLeft}/{@link #addRight}로
 * 넣고, 나중에 글자를 바꾸려면 돌려받은 {@link JLabel}의 {@code setText}를 쓴다.
 * 그래서 SSH 연결 수처럼 아직 읽을 방법이 없는 항목은 {@link ComingSoon}을 씌운 라벨로 자리만 둘 수 있다.
 */
public class StatusBar extends JPanel {

    private static final int BAR_HEIGHT = 26;
    private static final int PAD_X = 14;
    private static final int ITEM_GAP = 16;

    private final JPanel left = group();
    private final JPanel right = group();

    public StatusBar() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(0, PAD_X, 0, PAD_X));
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
    }

    /** 왼쪽 끝에서부터 차례로 놓는 글자 항목. 돌려받은 라벨로 나중에 글자를 바꾼다. */
    public JLabel addLeft(String text) {
        JLabel item = item(text);
        addTo(left, item);
        return item;
    }

    /** 왼쪽에 임의의 부품(예: 상태 점이 붙은 항목)을 놓는다. */
    public void addLeft(JComponent component) {
        addTo(left, component);
    }

    /** 오른쪽에 놓는 글자 항목. 먼저 넣은 것이 더 왼쪽에 온다. */
    public JLabel addRight(String text) {
        JLabel item = item(text);
        addTo(right, item);
        return item;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, BAR_HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, BAR_HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.PANEL_BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        // 위쪽 구분선
        g.setColor(Theme.DIVIDER);
        g.fillRect(0, 0, getWidth(), 1);
    }

    /** 항목을 세로 가운데에 놓는 가로 묶음. BoxLayout은 높이가 남으면 alignmentY(0.5)대로 가운데에 맞춘다. */
    private static JPanel group() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        return p;
    }

    private static JLabel item(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT_MUTED);
        label.setFont(label.getFont().deriveFont(11.5f));
        return label;
    }

    /** 첫 항목이 아니면 앞에 간격을 먼저 넣는다(맨 앞과 맨 뒤에는 여백이 생기지 않는다). */
    private static void addTo(JPanel box, JComponent component) {
        if (box.getComponentCount() > 0) {
            box.add(Box.createHorizontalStrut(ITEM_GAP));
        }
        component.setAlignmentY(CENTER_ALIGNMENT);
        box.add(component);
    }
}
