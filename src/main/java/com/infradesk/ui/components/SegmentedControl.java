package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ItemEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * 테두리 상자 안에 버튼이 나란히 있고 그중 하나만 선택되는 구간 선택 ("실시간 / 1시간 / 24시간 / 7일").
 *
 * <p>시안: 바깥 상자는 안쪽 여백 2, 테두리 1, 모서리 반지름 8. 버튼은 높이 26, 반지름 6,
 * 선택된 것은 {@code ACCENT} 채움에 흰 굵은 글자다.
 *
 * <p>모서리 값 주의: CSS의 {@code border-radius}는 <b>반지름</b>이고, Java의
 * {@code RoundRectangle2D}의 arc는 <b>지름</b>이다. 그래서 반지름 8은 arc 16으로 그린다.
 */
public class SegmentedControl extends JPanel {

    private static final int OUTER_ARC = 16;
    private static final int SEGMENT_HEIGHT = 26;
    private static final int SEGMENT_ARC = 12;
    private static final int SEGMENT_PAD_X = 12;
    /** 바깥 테두리 1 + 안쪽 여백 2. */
    private static final int FRAME = 3;

    private final ButtonGroup group = new ButtonGroup();
    private final List<Segment> segments = new ArrayList<>();
    private final List<IntConsumer> listeners = new ArrayList<>();

    /** @param labels 왼쪽부터 놓을 버튼 글자. 처음에는 첫 번째가 선택된다. */
    public SegmentedControl(List<String> labels) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(FRAME, FRAME, FRAME, FRAME));
        for (int i = 0; i < labels.size(); i++) {
            int index = i;
            Segment segment = new Segment(labels.get(i));
            // ItemListener는 선택이 "바뀔 때"만 불린다. 이미 선택된 버튼을 다시 눌러도 알리지 않는다.
            segment.addItemListener(e -> {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    listeners.forEach(l -> l.accept(index));
                }
            });
            group.add(segment);   // ButtonGroup이 "하나만 선택"을 보장한다.
            segments.add(segment);
            add(segment);
        }
        if (!segments.isEmpty()) {
            segments.getFirst().setSelected(true);
        }
    }

    /** 선택된 버튼의 번호(0부터). */
    public int getSelectedIndex() {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).isSelected()) {
                return i;
            }
        }
        return -1;
    }

    public void setSelectedIndex(int index) {
        segments.get(index).setSelected(true);
    }

    /**
     * 버튼 글자를 바꾼다. 개수가 붙는 구간 선택("전체 12", "경고 2")처럼 글자가 나중에 정해질 때 쓴다.
     * 폭이 바뀌므로 배치를 다시 한다.
     */
    public void setLabel(int index, String text) {
        segments.get(index).setText(text);
        revalidate();
        repaint();
    }

    /**
     * 칸 글자 앞에 작은 점을 붙인다(예: "● 실시간"의 초록 점). null이면 없앤다.
     */
    public void setDot(int index, java.awt.Color color) {
        segments.get(index).dot = color;
        segments.get(index).revalidate();
        segments.get(index).repaint();
    }

    /**
     * 칸 하나를 누를 수 없게 하거나 되살린다. 비활성인 칸은 흐리게 보이고 {@code tooltip}으로 이유를 알려 준다
     * (예: 직접 연결 서버는 1시간 외의 기간이 없다). 선택된 칸을 비활성으로 만들면 선택은 그대로 남는다.
     */
    public void setSegmentEnabled(int index, boolean enabled, String tooltip) {
        Segment s = segments.get(index);
        s.setEnabled(enabled);
        s.setToolTipText(enabled ? null : tooltip);
    }

    /** 선택이 바뀌면 새 번호와 함께 불린다. */
    public void addSelectionListener(IntConsumer listener) {
        listeners.add(listener);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (!isEnabled()) {
                g2.setComposite(AlphaComposite.SrcOver.derive(0.45f));
            }
            RoundRectangle2D box = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1,
                    OUTER_ARC, OUTER_ARC);
            g2.setColor(Theme.PANEL_BG);
            g2.fill(box);
            g2.setColor(Theme.INPUT_BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(box);
        } finally {
            g2.dispose();
        }
    }

    /** 상자 안의 버튼 하나. */
    private static final class Segment extends JToggleButton {

        /** 글자 앞의 점 색. null이면 점이 없다. */
        java.awt.Color dot;
        private static final int DOT = 6;
        private static final int DOT_GAP = 6;

        Segment(String label) {
            super(label);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFont(getFont().deriveFont(12.5f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private Font boldFont() {
            return getFont().deriveFont(Font.BOLD);
        }

        @Override
        public Dimension getPreferredSize() {
            // 선택되면 글자가 굵어지는데, 굵은 글자 기준으로 너비를 정해서 선택이 바뀌어도 버튼 폭이 흔들리지 않게 한다.
            int textWidth = getFontMetrics(boldFont()).stringWidth(getText()) + (dot == null ? 0 : DOT + DOT_GAP);
            return new Dimension(textWidth + SEGMENT_PAD_X * 2, SEGMENT_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                if (!isEnabled()) {
                    g2.setComposite(AlphaComposite.SrcOver.derive(0.45f));
                }
                int w = getWidth();
                int h = getHeight();
                boolean selected = isSelected();
                if (selected) {
                    g2.setColor(Theme.ACCENT);
                    g2.fillRoundRect(0, 0, w, h, SEGMENT_ARC, SEGMENT_ARC);
                } else if (getModel().isRollover() && isEnabled()) {
                    g2.setColor(Theme.HOVER_BG);
                    g2.fillRoundRect(0, 0, w, h, SEGMENT_ARC, SEGMENT_ARC);
                }
                g2.setFont(selected ? boldFont() : getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(selected ? java.awt.Color.WHITE : Theme.TEXT_SECONDARY);
                // 점이 있으면 점과 글자를 한 덩어리로 보고 그 덩어리를 가운데에 놓는다.
                int contentWidth = fm.stringWidth(getText()) + (dot == null ? 0 : DOT + DOT_GAP);
                int x = (w - contentWidth) / 2;
                if (dot != null) {
                    g2.setColor(dot);
                    g2.fillOval(x, (h - DOT) / 2, DOT, DOT);
                    g2.setColor(selected ? java.awt.Color.WHITE : Theme.TEXT_SECONDARY);
                    x += DOT + DOT_GAP;
                }
                // 세로는 기준선 = 위 여백 + 올림.
                g2.drawString(getText(), x, (h - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }
}
