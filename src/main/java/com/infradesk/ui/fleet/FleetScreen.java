package com.infradesk.ui.fleet;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.OverviewView;
import com.infradesk.core.Server;
import com.infradesk.core.ServerGrouping;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.FleetService;
import com.infradesk.service.ServerGroups;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.FilterChip;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.components.SegmentedControl;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * 앱의 첫 화면 "개요" (디자인 v2): 모든 서버를 한눈에. 위에서부터 요약 네 칸, "확인이 필요해요", 서버 카드 격자다.
 *
 * <p>이 화면은 값을 직접 읽지 않는다. 서버 목록, CPU, SSH로 읽은 값은 {@link #setData}로 받고, 읽기는 {@code
 * FleetService}가 한다(한 번에 2대씩, 값은 5분 재사용). 카드의 버튼은 그 서버 화면의 탭으로 이동만 한다.
 *
 * <p>아직 정해지지 않은 것은 준비 중으로 둔다: "확인 필요"를 판단하는 규칙(요약 칸, 목록, 거르기).
 * 서버는 카드 또는 목록({@link ServerTable})으로 보이고, 둘 다 같은 {@link CardData}를 그린다.
 */
public final class FleetScreen extends JPanel implements javax.swing.Scrollable {

    /** 상태로 거르기. */
    enum Filter { ALL, RUNNING, STOPPED }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final JLabel subtitle = new JLabel(" ");
    private final JLabel readAt = new JLabel(" ");
    private final JButton refresh = Buttons.secondary("새로고침", "refresh");
    private final JPanel summary = new JPanel(new CardGridLayout(170, 12, true));
    /** 서버 카드가 들어가는 곳. 묶음마다 머리글과 카드 격자를 위에서 아래로 쌓는다(묶지 않으면 격자 하나). */
    private final JPanel grid = new JPanel();
    private final JComboBox<String> groupBy = new JComboBox<>();
    /** 지금 그려진 묶음 머리글의 글자(시험용). */
    private final List<String> sectionTitles = new ArrayList<>();
    /** 묶는 방법을 코드로 바꾸는 중이면 true: 선택 목록의 알림을 사용자가 고른 것으로 오해하지 않기 위해. */
    private boolean settingGrouping;
    private ServerGrouping grouping = ServerGrouping.ACCOUNT;
    private Consumer<ServerGrouping> onGroupingPicked = g -> { };
    /** "카드 | 목록" 전환. 번호는 {@link OverviewView#values()}의 순서와 같다. */
    private final SegmentedControl view = new SegmentedControl(
            java.util.Arrays.stream(OverviewView.values()).map(OverviewView::label).toList());
    private OverviewView overviewView = OverviewView.CARDS;
    /** 읽기 모드면 카드와 목록에서 서버에 접속하거나 서버를 켜는 버튼을 숨긴다. */
    private boolean readOnly;
    /** 보기 방식을 코드로 바꾸는 중이면 true(사용자가 누른 것으로 오해하지 않기 위해). */
    private boolean settingView;
    private Consumer<OverviewView> onViewPicked = v -> { };
    private final FilterChip all = new FilterChip("전체");
    private final FilterChip running = new FilterChip("실행 중");
    private final FilterChip stopped = new FilterChip("정지");
    private final JPanel emptyNote = new JPanel(new BorderLayout());

    private Filter filter = Filter.ALL;
    private List<AccountInventory> inventory = List.of();
    private Map<String, Double> cpu = Map.of();
    private Function<String, Optional<FleetService.Reading>> readings = id -> Optional.empty();
    private Predicate<String> configured = id -> false;

    private Consumer<Server> onOpenServer = s -> { };
    private BiConsumer<Server, String> onOpenTab = (s, tab) -> { };
    private Consumer<Server> onStart = s -> { };
    private Runnable onRefresh = () -> { };

    public FleetScreen() {
        super(new BorderLayout());
        setBackground(Theme.APP_BG);
        setBorder(BorderFactory.createEmptyBorder(24, 28, 32, 28));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(header());
        column.add(Box.createVerticalStrut(18));
        summary.setOpaque(false);
        summary.setAlignmentX(LEFT_ALIGNMENT);
        column.add(summary);
        column.add(Box.createVerticalStrut(22));
        column.add(attentionSection());
        column.add(Box.createVerticalStrut(24));
        column.add(serversHeader());
        column.add(Box.createVerticalStrut(12));
        grid.setOpaque(false);
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
        grid.setAlignmentX(LEFT_ALIGNMENT);
        column.add(grid);
        column.add(Box.createVerticalStrut(8));
        emptyNote.setOpaque(false);
        emptyNote.setAlignmentX(LEFT_ALIGNMENT);
        column.add(emptyNote);
        add(column, BorderLayout.NORTH);
    }

    // ── 연결 ────────────────────────────────────────────────────

    public void onOpenServer(Consumer<Server> listener) {
        this.onOpenServer = listener;
    }

    /** 카드의 [SSH] [파일] [모니터링]: 그 서버 화면의 탭({@link com.infradesk.ui.ServerScreen}의 TAB_*)으로 간다. */
    public void onOpenTab(BiConsumer<Server, String> listener) {
        this.onOpenTab = listener;
    }

    public void onStart(Consumer<Server> listener) {
        this.onStart = listener;
    }

    public void onRefresh(Runnable listener) {
        this.onRefresh = listener;
    }

    /** 사용자가 위쪽의 "묶기" 목록에서 방법을 골랐다. */
    public void onGroupingPicked(Consumer<ServerGrouping> listener) {
        this.onGroupingPicked = listener;
    }

    /** 읽기 / 관리 모드를 바꾼다. 읽기 모드에서는 [SSH] [파일] [시작]이 빠지고 [모니터링]만 남는다. */
    public void setReadOnly(boolean readOnly) {
        if (this.readOnly != readOnly) {
            this.readOnly = readOnly;
            invalidateTree(this);
            rebuildGrid();
        }
    }

    /** 사용자가 위쪽의 "카드 | 목록"에서 보기 방식을 골랐다. */
    public void onViewPicked(Consumer<OverviewView> listener) {
        this.onViewPicked = listener;
    }

    /** 보기 방식(카드 / 목록)을 바꾸고 다시 그린다(앱 설정이 바뀌었을 때도 불린다). */
    public void setView(OverviewView value) {
        boolean changed = this.overviewView != value;
        this.overviewView = value;
        settingView = true;
        try {
            view.setSelectedIndex(value.ordinal());
        } finally {
            settingView = false;
        }
        if (changed) {
            invalidateTree(this);
            rebuildGrid();
        }
    }

    /** 묶는 방법을 바꾸고 카드를 다시 그린다(설정 "일반"에서 바뀌었을 때도 불린다). */
    public void setGrouping(ServerGrouping grouping) {
        boolean changed = this.grouping != grouping;
        this.grouping = grouping;
        settingGrouping = true;
        try {
            groupBy.setSelectedItem(grouping.label());   // 저장하지 못해 되돌릴 때는 목록만 맞추면 된다
        } finally {
            settingGrouping = false;
        }
        if (changed) {
            invalidateTree(this);
            rebuildGrid();
        }
    }

    /** 서버 정보를 읽는 중이면 [새로고침]을 잠근다. */
    public void setReading(boolean reading) {
        refresh.setEnabled(!reading);
        refresh.setText(reading ? "읽는 중…" : "새로고침");
    }

    /** 마지막으로 읽은 시각. null이면 지운다. */
    public void setReadAt(LocalTime at) {
        readAt.setText(at == null ? " " : at.format(CLOCK) + "에 읽음");
    }

    /**
     * @param cpu        서버 id별 최근 CPU %
     * @param readings   서버 id별 SSH로 읽은 값
     * @param configured 그 서버에 SSH 설정이 있는지
     */
    public void setData(List<AccountInventory> inventory, Map<String, Double> cpu,
                        Function<String, Optional<FleetService.Reading>> readings, Predicate<String> configured) {
        this.inventory = List.copyOf(inventory);
        this.cpu = Map.copyOf(cpu);
        this.readings = readings;
        this.configured = configured;
        rebuild();
    }

    // ── 위쪽 ────────────────────────────────────────────────────

    private JPanel header() {
        JLabel title = new JLabel("개요");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        subtitle.setForeground(Theme.TEXT_SECONDARY);
        readAt.setForeground(Theme.TEXT_MUTED);
        readAt.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        refresh.setToolTipText("서버 정보를 SSH로 다시 읽어요 (실행 중이고 SSH가 설정된 서버만)");
        refresh.addActionListener(e -> onRefresh.run());

        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        title.setAlignmentX(LEFT_ALIGNMENT);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);
        texts.add(title);
        texts.add(Box.createVerticalStrut(4));
        texts.add(subtitle);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(texts, BorderLayout.CENTER);
        p.add(row(10, readAt, refresh), BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel tile(String name, String value, String sub, boolean comingSoon) {
        RoundedPanel tile = new RoundedPanel(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        tile.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel n = new JLabel(name);
        n.setForeground(Theme.TEXT_SECONDARY);
        n.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        JLabel v = new JLabel(value);
        v.setFont(v.getFont().deriveFont(Font.BOLD, 24f));
        v.setForeground(comingSoon ? Theme.TEXT_MUTED : Theme.TEXT);
        JLabel s = new JLabel(sub);
        s.setForeground(Theme.TEXT_MUTED);
        JPanel line = row(6, v, s);
        line.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(n, BorderLayout.WEST);
        if (comingSoon) {
            top.add(ComingSoon.badge(), BorderLayout.EAST);
        }
        tile.add(top, BorderLayout.NORTH);
        tile.add(line, BorderLayout.CENTER);
        return tile;
    }

    /** "확인이 필요해요": 무엇을 확인 필요로 볼지 정하는 규칙이 아직 없다. */
    private JPanel attentionSection() {
        JLabel title = new JLabel("확인이 필요해요");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        // 준비 중(v2): 확인 필요 규칙(디스크 85% 이상, 멈춘 컨테이너, 계정 연결 실패)은 정한 뒤에 이 목록을 채운다
        JPanel head = row(8, title, ComingSoon.badge());

        RoundedPanel box = new RoundedPanel(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        box.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel text = new JLabel("무엇을 확인이 필요한 일로 볼지 아직 정하지 않았어요. 정해지면 이곳에 모아서 보여 줘요.");
        text.setForeground(Theme.TEXT_MUTED);
        box.add(text, BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(head, BorderLayout.NORTH);
        p.add(box, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JPanel serversHeader() {
        JLabel title = new JLabel("서버");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));

        ButtonGroup group = new ButtonGroup();
        running.setStatusDot(true);
        stopped.setStatusDot(false);
        for (FilterChip chip : new FilterChip[] {all, running, stopped}) {
            group.add(chip);
        }
        all.setSelected(true);
        all.addActionListener(e -> pick(Filter.ALL));
        running.addActionListener(e -> pick(Filter.RUNNING));
        stopped.addActionListener(e -> pick(Filter.STOPPED));
        // 준비 중(v2): "확인 필요" 칩은 규칙이 정해진 뒤에 붙인다

        for (ServerGrouping g : ServerGrouping.values()) {
            groupBy.addItem(g.label());
        }
        // macOS 기본 모양(위아래 화살표) 대신 시안처럼 아래 화살표 하나로(설정의 선택 목록과 같다).
        groupBy.putClientProperty(FlatClientProperties.STYLE, "buttonStyle: button");
        groupBy.addActionListener(e -> {
            if (!settingGrouping) {
                // 고른 값은 앱 설정에 저장된다. 돌아오는 setGrouping()이 화면을 다시 그린다.
                onGroupingPicked.accept(ServerGrouping.fromLabel((String) groupBy.getSelectedItem(), grouping));
            }
        });
        JLabel groupLabel = new JLabel("묶기");
        groupLabel.setForeground(Theme.TEXT_SECONDARY);
        view.addSelectionListener(index -> {
            if (!settingView) {
                // 고른 값은 앱 설정에 저장된다. 돌아오는 setView()가 화면을 다시 그린다.
                onViewPicked.accept(OverviewView.values()[index]);
            }
        });

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(row(12, title, row(6, all, running, stopped)), BorderLayout.WEST);
        p.add(row(8, groupLabel, groupBy, view), BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** 부품을 가로로 늘어놓고 가운데 맞춘다. 줄이 접히지 않으므로 높이가 폭에 달라지지 않는다. */
    private static JPanel row(int gap, Component... parts) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                p.add(Box.createHorizontalStrut(gap));
            }
            if (parts[i] instanceof javax.swing.JComponent c) {
                c.setAlignmentY(CENTER_ALIGNMENT);
            }
            p.add(parts[i]);
        }
        return p;
    }

    private void pick(Filter f) {
        filter = f;
        rebuildGrid();
    }

    // ── 다시 그리기 ─────────────────────────────────────────────

    private void rebuild() {
        FleetSummary sum = FleetSummary.of(inventory, cpu, readings);
        subtitle.setText(sum.servers() == 0 ? "등록된 서버가 없어요" : IpPrivacy.mask(sum.subtitle()));

        summary.removeAll();
        summary.add(tile("실행 중", String.valueOf(sum.running()), "/ " + sum.servers() + "대", false));
        // 준비 중(v2): 확인 필요 건수. 규칙이 정해지면 건수와 노란 테두리를 쓴다
        summary.add(tile("확인 필요", "—", "", true));
        summary.add(tile("평균 CPU (실행 중)", sum.averageCpuText(), "", false));
        summary.add(tile("컨테이너 실행", sum.containersText(), sum.containersSub(), false));
        summary.revalidate();

        all.setCount(sum.servers());
        running.setCount(sum.running());
        stopped.setCount(sum.servers() - sum.running());
        // 개수가 붙으면 칩이 넓어진다. 아직 한 번도 배치되지 않았으면 위쪽에 남은 크기 계산이 낡았으므로 모두 다시 잰다.
        invalidateTree(this);
        rebuildGrid();
    }

    private void rebuildGrid() {
        grid.removeAll();
        sectionTitles.clear();
        int shown = 0;
        // 위의 칩(전체 / 실행 중 / 정지)으로 거른 서버를, 고른 방법으로 묶는다(규칙은 ServerGroups, 사이드바와 같다).
        Predicate<Server> keep = server -> {
            boolean isRunning = server.status() == ServerStatus.RUNNING;
            return !(filter == Filter.RUNNING && !isRunning || filter == Filter.STOPPED && isRunning);
        };
        for (ServerGroups.Group group : ServerGroups.of(inventory, grouping, keep, false)) {
            // 카드와 목록은 같은 값(CardData)을 그린다: 보기 방식을 바꿔도 서버를 새로 읽지 않는다.
            List<CardData> values = new ArrayList<>();
            for (ServerGroups.Member member : group.members()) {
                Server server = member.server();
                values.add(CardData.of(server, member.inventory().account(), cpu.get(server.id()),
                        readings.apply(server.id()), configured.test(server.id())));
            }
            shown += values.size();
            JComponent body;
            if (overviewView == OverviewView.LIST) {
                body = new ServerTable(values, onOpenServer, onOpenTab, onStart, readOnly);
            } else {
                JPanel cards = new JPanel(new CardGridLayout(280, 12));
                cards.setOpaque(false);
                values.forEach(data -> cards.add(new ServerCard(data, onOpenServer, onOpenTab, onStart, readOnly)));
                body = cards;
            }
            body.setAlignmentX(LEFT_ALIGNMENT);
            String title = group.account() != null ? ServerGroups.accountTitle(group.account().account()) : group.title();
            if (title != null) {
                sectionTitles.add(title);
                grid.add(sectionHeader(title, group.members().size()));
                grid.add(Box.createVerticalStrut(8));
            }
            grid.add(body);
            grid.add(Box.createVerticalStrut(20));
        }
        emptyNote.removeAll();
        if (shown == 0) {
            JLabel none = new JLabel(inventory.isEmpty() ? "계정을 추가하면 서버가 여기에 보여요." : "이 조건에 맞는 서버가 없어요.");
            none.setForeground(Theme.TEXT_MUTED);
            emptyNote.add(none, BorderLayout.WEST);
        }
        grid.revalidate();
        grid.repaint();
        emptyNote.revalidate();
    }

    /** 묶음의 머리글: 이름과 서버 수. */
    private static JPanel sectionHeader(String title, int count) {
        JLabel name = new JLabel(title);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
        name.setForeground(Theme.TEXT_SECONDARY);
        JLabel n = new JLabel(String.valueOf(count));
        n.setForeground(Theme.TEXT_MUTED);
        JPanel p = row(8, name, n);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** 시험용: 지금 보이는 카드의 값(묶음 순서대로). */
    List<CardData> cards() {
        List<CardData> out = new ArrayList<>();
        for (Component section : grid.getComponents()) {
            if (section instanceof java.awt.Container container) {
                for (Component c : container.getComponents()) {
                    if (c instanceof ServerCard card) {
                        out.add(card.data());
                    }
                }
            }
        }
        return out;
    }

    /** 시험용: 목록 보기에서 지금 보이는 줄의 값(묶음 순서대로). 카드 보기면 비어 있다. */
    List<CardData> rows() {
        List<CardData> out = new ArrayList<>();
        for (Component c : grid.getComponents()) {
            if (c instanceof ServerTable table) {
                out.addAll(table.data());
            }
        }
        return out;
    }

    /** 시험용: 지금 보이는 묶음 머리글의 글자(묶지 않으면 비어 있다). */
    List<String> sectionTitles() {
        return List.copyOf(sectionTitles);
    }

    // ── 스크롤 ──────────────────────────────────────────────────

    /** 마지막으로 높이를 잰 폭. */
    private int measuredWidth = -1;

    /**
     * 폭이 바뀌었으면 안쪽의 잰 크기를 모두 버리고 다시 잰다. 격자의 칸 수가 폭에 따라 달라져서 높이도 달라지는데,
     * 스크롤 창은 폭을 정하기 전에 높이를 먼저 물으므로 지난 폭으로 잰 값이 남아 있으면 아래가 잘린다.
     */
    @Override
    public Dimension getPreferredSize() {
        int width = getParent() instanceof javax.swing.JViewport viewport ? viewport.getWidth() : getWidth();
        if (width != measuredWidth) {
            measuredWidth = width;
            invalidateTree(this);
        }
        return super.getPreferredSize();
    }

    private static void invalidateTree(java.awt.Container c) {
        c.invalidate();
        for (Component child : c.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                invalidateTree(inner);
            }
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
        return r.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /** 이 화면을 스크롤 창에 담는다. */
    public static JScrollPane scrolling(FleetScreen screen) {
        JScrollPane scroll = new JScrollPane(screen);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(Theme.APP_BG);
        return scroll;
    }
}
