package com.infradesk.ui.fleet;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.FleetService;
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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * 앱의 첫 화면 "개요" (디자인 v2): 모든 서버를 한눈에. 위에서부터 요약 네 칸, "확인이 필요해요", 서버 카드 격자다.
 *
 * <p>이 화면은 값을 직접 읽지 않는다. 서버 목록, CPU, SSH로 읽은 값은 {@link #setData}로 받고, 읽기는 {@code
 * FleetService}가 한다(한 번에 2대씩, 값은 5분 재사용). 카드의 버튼은 그 서버 화면의 탭으로 이동만 한다.
 *
 * <p>아직 정해지지 않은 것은 준비 중으로 둔다: "확인 필요"를 판단하는 규칙(요약 칸, 목록, 거르기), 묶는 방법, 목록 보기.
 */
public final class FleetScreen extends JPanel implements javax.swing.Scrollable {

    /** 상태로 거르기. */
    enum Filter { ALL, RUNNING, STOPPED }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final JLabel subtitle = new JLabel(" ");
    private final JLabel readAt = new JLabel(" ");
    private final JButton refresh = Buttons.secondary("새로고침", "refresh");
    private final JPanel summary = new JPanel(new CardGridLayout(170, 12, true));
    private final JPanel grid = new JPanel(new CardGridLayout(280, 12));
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

        JComboBox<String> groupBy = new JComboBox<>(new String[] {"계정별"});
        // 준비 중(v2): 상태별·묶지 않음
        ComingSoon.apply(groupBy);
        JLabel groupLabel = new JLabel("묶기");
        groupLabel.setForeground(Theme.TEXT_SECONDARY);
        SegmentedControl view = new SegmentedControl(List.of("카드", "목록"));
        // 준비 중(v2): 목록 보기
        view.setSegmentEnabled(1, false, "목록 보기는 준비 중이에요");

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
        int shown = 0;
        for (AccountInventory inv : inventory) {
            Account account = inv.account();
            for (Server server : inv.servers()) {
                boolean isRunning = server.status() == ServerStatus.RUNNING;
                if (filter == Filter.RUNNING && !isRunning || filter == Filter.STOPPED && isRunning) {
                    continue;
                }
                CardData data = CardData.of(server, account, cpu.get(server.id()), readings.apply(server.id()),
                        configured.test(server.id()));
                grid.add(new ServerCard(data, onOpenServer, onOpenTab, onStart));
                shown++;
            }
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

    /** 시험용: 지금 보이는 카드의 값. */
    List<CardData> cards() {
        List<CardData> out = new ArrayList<>();
        for (Component c : grid.getComponents()) {
            if (c instanceof ServerCard card) {
                out.add(card.data());
            }
        }
        return out;
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
