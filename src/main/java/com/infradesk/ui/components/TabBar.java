package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ItemEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * 밑줄로 선택을 보여 주는 탭 줄 ("개요 / 모니터링 / 컨테이너 4 / 파일 / 터미널 2 / 로그 준비 중").
 * 서버 화면 머리 아래에 놓는다 (디자인 v2).
 *
 * <p>탭마다 문자열 {@code id}를 준다. 화면 코드는 번호가 아니라 id("overview", "containers" …)로
 * 어느 탭인지 말한다. 탭이 늘거나 순서가 바뀌어도 코드가 깨지지 않게 하려는 것이다.
 *
 * <p>모양(시안 기준): 탭 안쪽 여백 위아래 9, 좌우 12, 탭 사이 2, 글자 13.
 * 선택된 탭은 글자가 굵고 아래에 2px {@code ACCENT_LINE} 밑줄이 있다.
 * 줄 전체의 맨 아래에는 1px {@code DIVIDER} 선이 가로로 깔린다.
 */
public class TabBar extends JPanel {

    // 이름을 HEIGHT로 짓지 않는다: 안쪽 클래스 Tab은 JToggleButton을 상속하는데, AWT의 ImageObserver가
    // HEIGHT(= 2)라는 상수를 물려주고, 상속받은 이름이 바깥 클래스의 상수보다 우선해서 탭 높이가 조용히 2가 된다.
    private static final int TAB_HEIGHT = 38;
    private static final int PAD_X = 12;
    /** 글자와 개수 배지(또는 "준비 중") 사이 간격. */
    private static final int GAP = 6;
    private static final int UNDERLINE = 2;
    private static final int COUNT_HEIGHT = 16;
    private static final int COUNT_PAD_X = 6;

    private final ButtonGroup group = new ButtonGroup();
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private Consumer<String> listener = id -> { };

    public TabBar() {
        super(new FlowLayout(FlowLayout.LEFT, 2, 0));
        setOpaque(false);
    }

    /** 탭을 오른쪽 끝에 추가한다. 맨 처음 추가한 탭이 처음에 선택된다. */
    public void addTab(String id, String label) {
        Tab tab = new Tab(id, label);
        tab.addItemListener(e -> {
            // 선택이 "바뀔 때"만 알린다. 이미 선택된 탭을 다시 눌러도 알리지 않는다.
            if (e.getStateChange() == ItemEvent.SELECTED) {
                listener.accept(id);
            }
        });
        group.add(tab);
        tabs.put(id, tab);
        add(tab);
        if (tabs.size() == 1) {
            tab.setSelected(true);
        }
    }

    /** 탭 이름 옆에 작은 개수 배지를 붙인다 (예: 컨테이너 4). null이면 없앤다. */
    public void setCount(String id, Integer count) {
        Tab tab = tab(id);
        tab.count = count;
        tab.revalidate();
        tab.repaint();
    }

    /** 아직 기능이 없는 탭으로 표시한다: 눌리지 않고, "준비 중" 글자와 안내가 붙는다. */
    public void setComingSoon(String id) {
        Tab tab = tab(id);
        tab.comingSoon = true;
        ComingSoon.apply(tab);
        tab.revalidate();
        tab.repaint();
    }

    /** 선택된 탭의 id. 탭이 없으면 null. */
    public String selectedId() {
        for (Map.Entry<String, Tab> e : tabs.entrySet()) {
            if (e.getValue().isSelected()) {
                return e.getKey();
            }
        }
        return null;
    }

    public void select(String id) {
        tab(id).setSelected(true);
    }

    /** 선택이 바뀌면 새 탭의 id와 함께 불린다. */
    public void onSelect(Consumer<String> listener) {
        this.listener = listener;
    }

    private Tab tab(String id) {
        Tab t = tabs.get(id);
        if (t == null) {
            throw new IllegalArgumentException("없는 탭: " + id);
        }
        return t;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, TAB_HEIGHT);
    }

    /** 줄 전체의 맨 아래 가로선. 선택된 탭의 밑줄이 이 위에 겹쳐 그려진다. */
    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.DIVIDER);
        g.fillRect(0, getHeight() - 1, getWidth(), 1);
    }

    /** 탭 하나. */
    private static final class Tab extends JToggleButton {

        final String id;
        Integer count;
        boolean comingSoon;

        Tab(String id, String label) {
            super(label);
            this.id = id;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFont(getFont().deriveFont(13f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private Font boldFont() {
            return getFont().deriveFont(Font.BOLD);
        }

        private Font smallFont() {
            return getFont().deriveFont(11f);
        }

        @Override
        public Dimension getPreferredSize() {
            // 선택되면 글자가 굵어지므로 굵은 글자 기준으로 폭을 잡아, 선택이 바뀌어도 옆 탭이 밀리지 않게 한다.
            int w = PAD_X * 2 + getFontMetrics(boldFont()).stringWidth(getText());
            FontMetrics small = getFontMetrics(smallFont());
            if (count != null) {
                w += GAP + small.stringWidth(String.valueOf(count)) + COUNT_PAD_X * 2;
            }
            if (comingSoon) {
                w += GAP + small.stringWidth("준비 중");
            }
            return new Dimension(w, TAB_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                boolean selected = isSelected();
                // 글자는 밑줄(2px)을 뺀 윗부분의 세로 가운데에 놓는다.
                int bodyHeight = h - UNDERLINE;

                if (!selected && getModel().isRollover() && isEnabled()) {
                    g2.setColor(Theme.HOVER_BG);
                    g2.fillRect(0, 0, w, bodyHeight);
                }

                g2.setFont(selected ? boldFont() : getFont());
                FontMetrics fm = g2.getFontMetrics();
                int baseline = (bodyHeight - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(comingSoon ? Theme.TEXT_MUTED : selected ? Theme.TEXT : Theme.TEXT_SECONDARY);
                g2.drawString(getText(), PAD_X, baseline);

                int x = PAD_X + fm.stringWidth(getText()) + GAP;
                g2.setFont(smallFont());
                FontMetrics small = g2.getFontMetrics();
                if (count != null) {
                    String text = String.valueOf(count);
                    int badgeW = small.stringWidth(text) + COUNT_PAD_X * 2;
                    int badgeY = (bodyHeight - COUNT_HEIGHT) / 2;
                    g2.setColor(Theme.DIVIDER);
                    g2.fillRoundRect(x, badgeY, badgeW, COUNT_HEIGHT, COUNT_HEIGHT, COUNT_HEIGHT);
                    g2.setColor(Theme.TEXT_SECONDARY);
                    g2.drawString(text, x + COUNT_PAD_X, badgeY + (COUNT_HEIGHT - small.getHeight()) / 2 + small.getAscent());
                }
                if (comingSoon) {
                    g2.setColor(Theme.TEXT_MUTED);
                    g2.drawString("준비 중", x, (bodyHeight - small.getHeight()) / 2 + small.getAscent());
                }

                if (selected) {
                    g2.setColor(Theme.ACCENT_LINE);
                    g2.fillRect(0, h - UNDERLINE, w, UNDERLINE);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
