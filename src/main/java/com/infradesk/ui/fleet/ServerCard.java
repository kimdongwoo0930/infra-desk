package com.infradesk.ui.fleet;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Icons;
import com.infradesk.ui.ServerScreen;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.Meter;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.components.StatusDot;
import com.infradesk.ui.components.WrapLayout;
import com.infradesk.ui.components.WrappingLabel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 개요의 서버 카드 하나 (디자인 v2): 상태 점과 이름, 이름 아래 한 줄, CPU·메모리·디스크 막대, 컨테이너와 업타임,
 * [SSH] [파일] [모니터링]. 정지된 서버는 막대 대신 안내 글자와 [시작]을 보인다.
 *
 * <p>카드를 누르면 그 서버의 화면으로 가고, 버튼은 그 서버 화면의 해당 탭(터미널, 파일, 모니터링)으로 간다. 버튼이 서버에
 * 접속하지는 않는다. 접속은 그 탭이 필요할 때 한다.
 */
final class ServerCard extends RoundedPanel {

    private static final Color NORMAL = Theme.PANEL_BG;
    private static final Color HOVER = new Color(0x2F3135);
    /** 안내 글자의 줄바꿈 폭에서 카드 안쪽 여백(16×2)과 안내 상자의 여백(14×2)을 뺀 값. */
    private static final int NOTE_INSET = 32 + 28;

    private final CardData data;
    private WrappingLabel note;

    /**
     * @param openServer 카드를 눌렀다(그 서버의 개요 탭)
     * @param openTab    버튼을 눌렀다(서버, {@link ServerScreen}의 탭 이름)
     * @param start      [시작]을 눌렀다
     */
    ServerCard(CardData data, java.util.function.Consumer<com.infradesk.core.Server> openServer,
               BiConsumer<com.infradesk.core.Server, String> openTab,
               java.util.function.Consumer<com.infradesk.core.Server> start) {
        super(new BorderLayout(), NORMAL, Theme.DIVIDER, Theme.ARC_CARD);
        this.data = data;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(data.server().name() + ", " + data.server().status().label());

        add(header(), BorderLayout.NORTH);
        add(data.running() ? runningBody() : stoppedBody(), BorderLayout.CENTER);
        add(buttons(openTab, start), BorderLayout.SOUTH);

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openServer.accept(data.server());
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                setBackground(HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // 카드 안의 다른 부품으로 옮겨 가는 것은 나간 것이 아니다
                if (!contains(javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), ServerCard.this))) {
                    setBackground(NORMAL);
                }
            }
        };
        listenOnChildren(this, click);
    }

    CardData data() {
        return data;
    }

    /** 버튼을 뺀 나머지 부분에 누름·올림 감시를 단다(버튼은 자기 동작이 있다). */
    private static void listenOnChildren(java.awt.Container parent, MouseAdapter click) {
        parent.addMouseListener(click);
        for (java.awt.Component c : parent.getComponents()) {
            if (c instanceof JButton) {
                continue;
            }
            if (c instanceof java.awt.Container child) {
                listenOnChildren(child, click);
            } else {
                c.addMouseListener(click);
            }
        }
    }

    private JComponent header() {
        JLabel name = new JLabel(data.server().name());
        name.setIcon(new StatusDot(data.server().status()));
        name.setIconTextGap(10);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 14f));

        JLabel meta = new JLabel(IpPrivacy.mask(data.meta()));
        meta.setForeground(Theme.TEXT_MUTED);
        meta.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        // 이름 아래 줄은 점 아래가 아니라 이름 글자에 맞춘다(점 8 + 간격 10)
        meta.setBorder(BorderFactory.createEmptyBorder(2, 18, 0, 0));

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(name, BorderLayout.NORTH);
        p.add(meta, BorderLayout.CENTER);
        return p;
    }

    private JComponent runningBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        body.add(barRow("CPU", data.cpu()));
        body.add(Box.createVerticalStrut(8));
        body.add(barRow("메모리", data.memory()));
        body.add(Box.createVerticalStrut(8));
        body.add(barRow("디스크", data.disk()));
        if (!data.note().isEmpty()) {
            body.add(Box.createVerticalStrut(8));
            JLabel n = new JLabel(IpPrivacy.mask(data.note()));
            n.setForeground(Theme.TEXT_MUTED);
            n.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            n.setToolTipText(IpPrivacy.mask(data.note()));
            n.setAlignmentX(LEFT_ALIGNMENT);
            // 긴 안내가 카드를 넓히지 않고 "…"로 끝나게 한다
            n.setMinimumSize(new Dimension(0, n.getPreferredSize().height));
            body.add(n);
        }
        body.add(Box.createVerticalGlue());
        body.add(summaryRow());
        return body;
    }

    private JComponent barRow(String label, double percent) {
        JLabel l = new JLabel(label);
        l.setForeground(Theme.TEXT_SECONDARY);
        l.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        l.setPreferredSize(new Dimension(44, l.getPreferredSize().height));

        Meter meter = new Meter();
        meter.setPercent(Math.max(0, percent));
        boolean known = percent >= 0;
        JLabel value = new JLabel(known ? Math.round(percent) + "%" : "—", JLabel.RIGHT);
        value.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        // 색만으로 경고를 전하지 않는다: 85% 이상이면 숫자도 경고색에 굵게
        if (known && meter.isWarning()) {
            value.setForeground(Theme.WARNING_BADGE_TEXT);
            value.setFont(value.getFont().deriveFont(Font.BOLD));
        } else {
            value.setForeground(known ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        value.setPreferredSize(new Dimension(40, value.getPreferredSize().height));

        // 막대는 높이 6이라, 줄의 가운데에 놓는다
        JPanel middle = new JPanel(new GridBagLayout());
        middle.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        middle.add(meter, gc);

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.add(l, BorderLayout.WEST);
        row.add(middle, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.getAccessibleContext().setAccessibleName(label + " " + (known ? Math.round(percent) + "%" : "모름"));
        return row;
    }

    /** 컨테이너 수와 업타임이 있는 줄. 위에 가는 선을 긋는다. */
    private JComponent summaryRow() {
        JLabel containers = new JLabel(data.containers().isEmpty() ? "컨테이너 —" : data.containers());
        containers.setIcon(Icons.get("box", 14, Theme.TEXT_SECONDARY));
        containers.setIconTextGap(8);
        containers.setForeground(Theme.TEXT_SECONDARY);
        containers.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(containers);
        if (data.stopped() > 0) {
            JLabel warn = new JLabel("  · " + data.stopped() + "개 멈춤");
            warn.setForeground(Theme.WARNING);
            warn.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            left.add(warn);
        }
        JLabel uptime = new JLabel(data.uptime());
        uptime.setForeground(Theme.TEXT_MUTED);
        uptime.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(14, 0, 0, 0),
                BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                        BorderFactory.createEmptyBorder(12, 0, 12, 0))));
        row.add(left, BorderLayout.WEST);
        row.add(uptime, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private JComponent stoppedBody() {
        note = new WrappingLabel(data.note(), 200, -1, Theme.TEXT_SECONDARY);
        RoundedPanel box = new RoundedPanel(new BorderLayout(), Theme.PANEL_LOW_BG, null, 6);
        box.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        box.add(note, BorderLayout.NORTH);
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(14, 0, 12, 0));
        p.add(box, BorderLayout.CENTER);
        return p;
    }

    private JComponent buttons(BiConsumer<com.infradesk.core.Server, String> openTab,
                               java.util.function.Consumer<com.infradesk.core.Server> start) {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 6, true));
        row.setOpaque(false);
        if (data.running()) {
            row.add(small("SSH", "terminal", e -> openTab.accept(data.server(), ServerScreen.TAB_TERMINAL)));
            row.add(small("파일", "folder", e -> openTab.accept(data.server(), ServerScreen.TAB_FILES)));
            row.add(small("모니터링", "chart", e -> openTab.accept(data.server(), ServerScreen.TAB_MONITORING)));
        } else if (data.canStart()) {
            row.add(small("시작", "play", e -> start.accept(data.server())));
        }
        return row;
    }

    /** 시안의 카드 버튼: 높이 28, 글자 12, 좌우 여백 10. */
    private static JButton small(String text, String icon, java.awt.event.ActionListener action) {
        JButton b = Buttons.secondary(text, icon);
        b.putClientProperty(FlatClientProperties.STYLE,
                "background: #00000000; borderWidth: 1; borderColor: #4E5157; hoverBackground: #FFFFFF10;"
                        + " pressedBackground: #FFFFFF18; margin: 2,8,2,8; font: -1");
        b.setPreferredSize(null);
        b.setPreferredSize(new Dimension(b.getPreferredSize().width, 28));
        b.addActionListener(action);
        return b;
    }

    @Override
    public void doLayout() {
        super.doLayout();
        if (note != null) {
            note.setWrapWidth(Math.max(120, getWidth() - NOTE_INSET));
        }
    }
}
