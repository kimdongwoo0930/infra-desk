package com.infradesk.ui.fleet;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.ServerScreen;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Meter;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.components.StatusDot;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 개요의 "목록" 보기: 서버 하나가 표의 한 줄이다. 위에 열 머리글, 줄마다 상태 점·이름과 그 아래 계정·리전·사양 / CPU /
 * 메모리 / 디스크 / 컨테이너 / 업타임 / 버튼. 카드와 같은 {@link CardData}를 그려서, 목록으로 바꿔도 서버를 새로 읽지 않는다.
 *
 * <p>줄을 누르면 그 서버 화면으로 가고, 버튼은 카드와 같다([SSH] [파일] [모니터링], 정지된 서버는 [시작]).
 * 정지된 서버는 막대 대신 상태 글자 하나를 CPU 열부터 디스크 열까지 펼쳐서 보인다.
 *
 * <p><b>좁아질 때:</b> 서버 이름과 버튼은 잘리지 않게 두고, 다른 것부터 줄인다. 순서는
 * <ol start="0">
 *   <li>{@link #LEVEL_FULL}: 모두 보인다.
 *   <li>{@link #LEVEL_NO_BARS}: CPU·메모리·디스크의 막대를 없애고 %만 보인다.
 *   <li>{@link #LEVEL_NO_META}: 이름 아래의 계정·리전·사양 줄을 없앤다.
 *   <li>{@link #LEVEL_NO_EXTRAS}: 컨테이너와 업타임 열을 없앤다.
 * </ol>
 * 어느 단계로 갈지는 지금 폭과 각 단계에 필요한 폭({@link #requiredWidth})을 견줘서 정한다.
 */
final class ServerTable extends RoundedPanel {

    static final int LEVEL_FULL = 0;
    static final int LEVEL_NO_BARS = 1;
    static final int LEVEL_NO_META = 2;
    static final int LEVEL_NO_EXTRAS = 3;

    // 열 폭. 첫 열(이름)만 남는 폭을 가져가고, 나머지는 고정이라 줄마다 열이 맞는다.
    private static final int BAR_W = 90;          // 막대와 %가 있는 CPU·메모리·디스크 칸
    private static final int PERCENT_W = 44;      // %만 있는 칸
    private static final int CONTAINERS_W = 100;
    private static final int UPTIME_W = 52;
    private static final int BUTTONS_W = 232;
    /** 읽기 모드에서는 [모니터링] 하나만 남아서 버튼 열이 좁다. */
    private static final int READ_ONLY_BUTTONS_W = 112;
    /** 이름 아래 계정·리전·사양 줄이 있을 때 첫 열이 적어도 가져야 하는 폭(줄임표로 줄어든다). */
    private static final int NAME_WITH_META_W = 170;
    private static final int ROW_H = 52;
    private static final int HEADER_H = 36;
    /** 열 사이 간격(칸마다 좌우 6). */
    private static final int GAP = 12;
    /** 표 안쪽 좌우 여백과 약간의 여유. */
    private static final int FRAME_W = 24;

    private final List<ServerRow> rows = new ArrayList<>();
    private final List<JLabel> meterHeaders = new ArrayList<>();
    private final List<JComponent> extraHeaders = new ArrayList<>();
    /** 가장 긴 서버 이름(점 포함)의 폭. 어느 단계에서도 이 폭은 줄이지 않는다. */
    private final int nameWidth;
    /** 버튼 열의 폭(읽기 모드는 좁다). */
    private final int buttonsWidth;
    private int level = LEVEL_FULL;

    /**
     * @param readOnly 읽기 모드: 서버에 접속하거나 서버를 켜는 버튼을 빼고 [모니터링]만 둔다
     */
    ServerTable(List<CardData> data, Consumer<Server> openServer, BiConsumer<Server, String> openTab,
                Consumer<Server> start, boolean readOnly) {
        super(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        this.buttonsWidth = readOnly ? READ_ONLY_BUTTONS_W : BUTTONS_W;
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
        column.add(header());
        int widest = 0;
        for (int i = 0; i < data.size(); i++) {
            ServerRow row = new ServerRow(data.get(i), i < data.size() - 1, readOnly, buttonsWidth, openServer, openTab, start);
            rows.add(row);
            column.add(row);
            widest = Math.max(widest, row.nameWidth());
        }
        nameWidth = widest;
        add(column, BorderLayout.CENTER);
    }

    // ── 폭에 따른 단계 ──────────────────────────────────────────

    /**
     * 그 단계로 그리는 데 필요한 표의 폭. 이름 열과 버튼 열은 어느 단계에서도 그대로이고, 단계가 올라갈수록 막대, 이름 아래
     * 줄, 컨테이너·업타임이 빠져서 필요한 폭이 줄어든다.
     *
     * @param nameWidth 가장 긴 서버 이름(점 포함)의 폭
     */
    static int requiredWidth(int level, int nameWidth) {
        return requiredWidth(level, nameWidth, BUTTONS_W);
    }

    /** @param buttonsWidth 버튼 열의 폭 */
    static int requiredWidth(int level, int nameWidth, int buttonsWidth) {
        int meter = level == LEVEL_FULL ? BAR_W : PERCENT_W;
        int name = level < LEVEL_NO_META ? Math.max(nameWidth, NAME_WITH_META_W) : nameWidth;
        boolean extras = level < LEVEL_NO_EXTRAS;
        int columns = 1 + 3 + (extras ? 2 : 0) + 1;
        return FRAME_W + name + 3 * meter + (extras ? CONTAINERS_W + UPTIME_W : 0) + buttonsWidth + columns * GAP;
    }

    /** 이 폭에서 쓸 단계: 필요한 폭이 들어가는 가장 낮은 단계. 가장 좁은 단계로도 안 들어가면 그 단계. */
    static int levelFor(int width, int nameWidth) {
        return levelFor(width, nameWidth, BUTTONS_W);
    }

    static int levelFor(int width, int nameWidth, int buttonsWidth) {
        for (int l = LEVEL_FULL; l < LEVEL_NO_EXTRAS; l++) {
            if (width >= requiredWidth(l, nameWidth, buttonsWidth)) {
                return l;
            }
        }
        return LEVEL_NO_EXTRAS;
    }

    @Override
    public void doLayout() {
        if (getWidth() > 0) {
            int wanted = levelFor(getWidth(), nameWidth, buttonsWidth);
            if (wanted != level) {
                applyLevel(wanted);
            }
        }
        super.doLayout();
    }

    private void applyLevel(int next) {
        level = next;
        int meter = next == LEVEL_FULL ? BAR_W : PERCENT_W;
        for (JLabel h : meterHeaders) {
            h.setPreferredSize(new Dimension(meter, h.getPreferredSize().height));
            h.setMinimumSize(new Dimension(meter, h.getPreferredSize().height));
        }
        extraHeaders.forEach(h -> h.setVisible(next < LEVEL_NO_EXTRAS));
        rows.forEach(r -> r.applyLevel(next));
        revalidate();
    }

    /** 시험용: 지금 쓰는 단계. */
    int level() {
        return level;
    }

    /** 시험용: 지금 그려진 줄의 값(위에서부터). */
    List<CardData> data() {
        return rows.stream().map(r -> r.data).toList();
    }

    // ── 머리글 ──────────────────────────────────────────────────

    private JComponent header() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));
        p.setPreferredSize(new Dimension(0, HEADER_H));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, HEADER_H));
        p.setAlignmentX(LEFT_ALIGNMENT);
        int x = 0;
        p.add(headerLabel("서버", 0), at(x++, 1, true));
        for (String title : new String[] {"CPU", "메모리", "디스크"}) {
            JLabel l = headerLabel(title, BAR_W);
            meterHeaders.add(l);
            p.add(l, at(x++, 1, false));
        }
        JLabel containers = headerLabel("컨테이너", CONTAINERS_W);
        JLabel uptime = headerLabel("업타임", UPTIME_W);
        extraHeaders.add(containers);
        extraHeaders.add(uptime);
        p.add(containers, at(x++, 1, false));
        p.add(uptime, at(x++, 1, false));
        p.add(headerLabel("", buttonsWidth), at(x, 1, false));
        return p;
    }

    private static JLabel headerLabel(String text, int width) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT_MUTED);
        l.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        if (width > 0) {
            l.setPreferredSize(new Dimension(width, l.getPreferredSize().height));
            l.setMinimumSize(new Dimension(width, l.getPreferredSize().height));
        } else {
            l.setMinimumSize(new Dimension(0, l.getPreferredSize().height));
        }
        return l;
    }

    private static GridBagConstraints at(int x, int width, boolean flex) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridwidth = width;
        c.weightx = flex ? 1 : 0;
        c.fill = flex ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new java.awt.Insets(0, x == 0 ? 8 : GAP / 2, 0, GAP / 2);
        return c;
    }

    // ── 줄 ──────────────────────────────────────────────────────

    /** CPU·메모리·디스크 칸 하나: 숫자와 그 뒤의 막대(막대는 좁을 때 없앤다). */
    private static final class PercentCell extends JPanel {

        private final JComponent bar;

        PercentCell(double percent, String note) {
            super(new BorderLayout(8, 0));
            boolean known = percent >= 0;
            Meter meter = new Meter();
            meter.setPercent(Math.max(0, percent));
            meter.setPreferredSize(new Dimension(34, 6));
            JLabel value = new JLabel(known ? Math.round(percent) + "%" : "—", JLabel.LEFT);
            value.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            // 색만으로 경고를 전하지 않는다: 85% 이상이면 숫자도 경고색에 굵게(막대가 없어도 보인다)
            if (known && meter.isWarning()) {
                value.setForeground(Theme.WARNING_BADGE_TEXT);
                value.setFont(value.getFont().deriveFont(Font.BOLD));
            } else {
                value.setForeground(known ? Theme.TEXT : Theme.TEXT_MUTED);
            }
            value.setPreferredSize(new Dimension(PERCENT_W - 8, value.getPreferredSize().height));

            JPanel middle = new JPanel(new GridBagLayout());   // 막대는 높이 6이라 줄의 가운데에 놓는다
            middle.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            middle.add(meter, gc);
            JPanel blank = new JPanel();   // 값을 모르면 막대 자리를 비운다
            blank.setOpaque(false);
            bar = known ? middle : blank;

            setOpaque(false);
            // 숫자를 막대 앞(왼쪽)에 둔다: 열 머리글 바로 아래에 있어야 옆 열의 막대에 붙어 보이지 않는다
            add(value, BorderLayout.WEST);
            add(bar, BorderLayout.CENTER);
            if (!known && note != null && !note.isEmpty()) {
                setToolTipText(IpPrivacy.mask(note));
            }
            getAccessibleContext().setAccessibleName(known ? Math.round(percent) + "%" : "모름");
            setBars(true);
        }

        void setBars(boolean show) {
            bar.setVisible(show);
            int w = show ? BAR_W : PERCENT_W;
            setPreferredSize(new Dimension(w, 20));
            setMinimumSize(new Dimension(w, 20));
        }
    }

    private static final class ServerRow extends JPanel {

        private final CardData data;
        private final List<PercentCell> percents = new ArrayList<>();
        private final JLabel meta;
        private final JComponent nameCell;
        private final JComponent containers;
        private final JComponent uptime;
        /** 정지·전환 중인 줄의 상태 글자(막대가 있는 줄이면 null). CPU부터 디스크까지 3칸 폭을 차지한다. */
        private final JLabel status;
        private final int nameWidth;
        private boolean hover;

        ServerRow(CardData data, boolean divider, boolean readOnly, int buttonsWidth, Consumer<Server> openServer,
                  BiConsumer<Server, String> openTab, Consumer<Server> start) {
            super(new GridBagLayout());
            this.data = data;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(0, ROW_H));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));
            if (divider) {
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));
            }
            getAccessibleContext().setAccessibleName(data.server().name() + ", " + data.server().status().label());

            JLabel name = new JLabel(data.server().name());
            name.setIcon(new StatusDot(data.server().status()));
            name.setIconTextGap(10);
            name.setFont(name.getFont().deriveFont(Font.BOLD, 14f));
            nameWidth = name.getPreferredSize().width;
            // 이름은 줄이지 않는다: 열이 아무리 좁아져도 이름 폭은 그대로 둔다
            name.setMinimumSize(new Dimension(nameWidth, name.getPreferredSize().height));

            meta = text(IpPrivacy.mask(data.meta()), Theme.TEXT_MUTED);
            meta.setBorder(BorderFactory.createEmptyBorder(2, 18, 0, 0));   // 이름 글자에 맞춘다(점 8 + 간격 10)
            meta.setMinimumSize(new Dimension(0, meta.getPreferredSize().height));   // 좁으면 "…"로 줄어든다

            JPanel namePanel = new JPanel(new BorderLayout());
            namePanel.setOpaque(false);
            namePanel.add(name, BorderLayout.NORTH);
            namePanel.add(meta, BorderLayout.CENTER);
            namePanel.setMinimumSize(new Dimension(nameWidth, namePanel.getPreferredSize().height));
            namePanel.setToolTipText(data.server().name());
            nameCell = namePanel;

            int x = 0;
            add(nameCell, at(x++, 1, true));
            if (data.running()) {
                status = null;
                percents.add(new PercentCell(data.cpu(), null));
                percents.add(new PercentCell(data.memory(), data.note()));
                percents.add(new PercentCell(data.disk(), data.note()));
                for (PercentCell cell : percents) {
                    add(cell, at(x++, 1, false));
                }
            } else {
                // 정지·전환 중: 막대 대신 상태 글자 하나를 CPU부터 디스크 열까지 펼친다
                status = text(data.server().status().label(), Theme.TEXT_SECONDARY);
                status.setToolTipText(data.note());
                sizeStatus(BAR_W);
                add(status, at(x, 3, false));
                x += 3;
            }
            containers = containers();
            uptime = fixed(text(data.uptime().replaceFirst("^업타임 ", ""), Theme.TEXT_MUTED), UPTIME_W);
            add(containers, at(x++, 1, false));
            add(uptime, at(x++, 1, false));
            add(buttons(readOnly, buttonsWidth, openTab, start), at(x, 1, false));

            MouseAdapter click = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openServer.accept(data.server());
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setHover(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    // 줄 안의 다른 부품으로 옮겨 가는 것은 나간 것이 아니다
                    if (!contains(javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), ServerRow.this))) {
                        setHover(false);
                    }
                }
            };
            ServerCard.listenOnChildren(this, click);
        }

        int nameWidth() {
            return nameWidth;
        }

        /**
         * 상태 글자 칸의 폭을 CPU·메모리·디스크 세 칸(과 그 사이 간격)에 맞춘다. 줄마다 격자가 따로여서, 이 폭을 정하지 않으면
         * 글자 길이만큼만 잡히고 남는 폭을 이름 열이 가져가 뒤의 열이 어긋난다.
         */
        private void sizeStatus(int cellWidth) {
            Dimension d = new Dimension(3 * cellWidth + 2 * GAP, status.getPreferredSize().height);
            status.setPreferredSize(d);
            status.setMinimumSize(d);
        }

        /** 좁아진 단계에 맞춰 막대, 이름 아래 줄, 컨테이너·업타임을 보이거나 숨긴다. 이름과 버튼은 그대로다. */
        void applyLevel(int level) {
            percents.forEach(p -> p.setBars(level == LEVEL_FULL));
            if (status != null) {
                sizeStatus(level == LEVEL_FULL ? BAR_W : PERCENT_W);
            }
            meta.setVisible(level < LEVEL_NO_META);
            containers.setVisible(level < LEVEL_NO_EXTRAS);
            uptime.setVisible(level < LEVEL_NO_EXTRAS);
            nameCell.setMinimumSize(new Dimension(nameWidth, nameCell.getPreferredSize().height));
        }

        private void setHover(boolean on) {
            hover = on;
            repaint();
        }

        private static JLabel text(String text, java.awt.Color color) {
            JLabel l = new JLabel(text);
            l.setForeground(color);
            l.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            l.setToolTipText(text.isEmpty() ? null : text);
            return l;
        }

        private static JLabel fixed(JLabel l, int width) {
            l.setPreferredSize(new Dimension(width, l.getPreferredSize().height));
            l.setMinimumSize(new Dimension(width, l.getPreferredSize().height));
            return l;
        }

        /** 컨테이너 수. 멈춘 것이 있으면 카드처럼 경고색으로 덧붙인다. 정지된 서버는 비운다. */
        private JComponent containers() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            p.setOpaque(false);
            if (data.running()) {
                String summary = data.containers().isEmpty() ? "—" : data.containers().replaceFirst("^컨테이너 ", "");
                p.add(text(summary, Theme.TEXT_SECONDARY));
                if (data.stopped() > 0) {
                    p.add(text("  · " + data.stopped() + "개 멈춤", Theme.WARNING));
                }
            }
            p.setPreferredSize(new Dimension(CONTAINERS_W, 20));
            p.setMinimumSize(new Dimension(CONTAINERS_W, 20));
            return p;
        }

        private JComponent buttons(boolean readOnly, int width, BiConsumer<Server, String> openTab, Consumer<Server> start) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            p.setOpaque(false);
            if (data.running()) {
                if (!readOnly) {
                    p.add(ServerCard.small("SSH", "terminal", e -> openTab.accept(data.server(), ServerScreen.TAB_TERMINAL)));
                    p.add(ServerCard.small("파일", "folder", e -> openTab.accept(data.server(), ServerScreen.TAB_FILES)));
                }
                p.add(ServerCard.small("모니터링", "chart", e -> openTab.accept(data.server(), ServerScreen.TAB_MONITORING)));
            } else if (data.canStart() && !readOnly) {
                p.add(ServerCard.small("시작", "play", e -> start.accept(data.server())));
            }
            // 버튼은 어느 단계에서도 줄이지 않는다
            p.setPreferredSize(new Dimension(width, 32));
            p.setMinimumSize(new Dimension(width, 32));
            return p;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hover) {
                // 표의 둥근 모서리를 넘치지 않게, 안쪽으로 들여 둥글게 칠한다
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.HOVER_BG);
                g2.fill(new RoundRectangle2D.Float(0, 2, getWidth(), getHeight() - 4, 10, 10));
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
