package com.infradesk.ui.containers;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.LogLine;
import com.infradesk.ssh.LogLine.Level;
import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.FilterChip;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.components.SegmentedControl;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 컨테이너 탭 아래쪽의 로그 패널 (디자인 v2). 한 번에 컨테이너 하나의 로그를 보여 준다.
 *
 * <pre>
 *  로그  [● discord-bot] [● nginx] [○ watchtower]                    [별도 창]
 *  [전체 12 | 경고 2 | 오류 2] [로그에서 찾기]   [따라가기] [최근 200줄 ▾] [줄바꿈] [새로고침]
 *  15:30:41  정보  "GET /health HTTP/1.1" 200 2 ...
 *  ...
 *  ● docker logs --tail 200 --timestamps nginx            보이는 줄 12 / 받은 줄 12 · 경고 2 · 오류 2
 * </pre>
 *
 * <p>이 클래스는 서버에 접속하지 않는다. 로그를 받아야 할 때(컨테이너를 고르거나, 줄 수를 바꾸거나,
 * 새로고침을 누르면) {@link #onReload}로 알리기만 하고, 받아 온 텍스트는 {@link #showLogs}로 건네받는다.
 * 그래서 화면 조각을 서버 없이 확인할 수 있고, 접속 규칙(SessionPool)은 이 클래스 밖에서 지켜진다.
 *
 * <p><b>"따라가기"</b>는 실시간 스트리밍({@code docker logs -f})이 아니다. 새로 읽어 올 때마다 맨 아래로
 * 스크롤하는 것이다. 멈춘 컨테이너는 따라갈 것이 없어서 꺼지고 눌리지 않는다. 실시간 스트리밍은 SSH 채널을
 * 계속 열어 두어야 해서 접속 규칙을 정한 뒤에 한다.
 */
public class ContainerLogView extends JPanel {

    private static final Integer[] TAIL_CHOICES = {200, 1000, 5000};
    private static final int CONTROL_HEIGHT = 28;
    private static final String CARD_TABLE = "table";
    private static final String CARD_MESSAGE = "message";

    private final ButtonGroup chipGroup = new ButtonGroup();
    private final JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final Map<String, FilterChip> chips = new LinkedHashMap<>();
    private final SegmentedControl levels = new SegmentedControl(List.of("전체", "경고", "오류"));
    private final JTextField search = new JTextField();
    private final JToggleButton follow = new JToggleButton("따라가기");
    private final JComboBox<Integer> tailBox = new JComboBox<>(TAIL_CHOICES);
    private final JToggleButton wrapButton = new JToggleButton("줄바꿈");
    private final JButton refresh = Buttons.icon("refresh", "로그 새로고침", CONTROL_HEIGHT);
    private final JButton openWindow = Buttons.icon("external", "별도 창으로 열기", CONTROL_HEIGHT);
    private final LogTable table = new LogTable();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel message = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel command = new JLabel(" ");
    private final JLabel summary = new JLabel(" ");

    /** 받은 줄 전부(거르기 전). */
    private List<LogLine> all = List.of();
    private Container current;
    /** 칩으로 보여 주는 컨테이너 목록(받은 순서). */
    private List<Container> containers = List.of();
    private BiConsumer<Container, Integer> onReload = (c, tail) -> { };
    private Consumer<Container> onOpenWindow = c -> { };

    public ContainerLogView() {
        super(new BorderLayout());
        setOpaque(false);

        // 카드: 바깥 모서리가 둥글다. RoundedPanel은 자식을 모서리에 맞춰 자르지 않으므로,
        // 사각 배경을 가진 로그 본문과 하단 막대가 모서리 밖으로 튀어나오지 않게 직접 자른다.
        RoundedPanel card = new RoundedPanel(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD) {
            @Override
            protected void paintChildren(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.clip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(),
                            Theme.ARC_CARD * 2f, Theme.ARC_CARD * 2f));
                    super.paintChildren(g2);
                } finally {
                    g2.dispose();
                }
            }
        };

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(headerRow());
        top.add(controlRow());
        card.add(top, BorderLayout.NORTH);
        card.add(bodyArea(), BorderLayout.CENTER);
        card.add(footer(), BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);

        wireEvents();
        showHint("컨테이너를 고르면 로그가 보여요");
        showSummary();
    }

    // ── 화면 조립 ───────────────────────────────────────────────

    private JPanel headerRow() {
        JLabel title = new JLabel("로그", Icons.get("file", 16), SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        title.setIconTextGap(8);
        chipRow.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(chipRow);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 12));
        row.add(left, BorderLayout.WEST);
        row.add(openWindow, BorderLayout.EAST);
        return row;
    }

    private JPanel controlRow() {
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "로그에서 찾기");
        search.setPreferredSize(new Dimension(240, CONTROL_HEIGHT));

        // 아이콘을 먼저 붙인다: 버튼 폭은 styleToggle에서 현재 크기로 정하므로, 나중에 붙이면 글자가 잘린다.
        follow.setIcon(dot(Theme.RUNNING_DOT));   // 시안: 따라가기 앞의 초록 점
        follow.setIconTextGap(6);
        styleToggle(follow, true);
        styleToggle(wrapButton, false);
        tailBox.setPreferredSize(new Dimension(112, CONTROL_HEIGHT));
        // 맥 룩앤필의 기본 콤보는 파란 위아래 화살표 버튼이다. 시안은 단순한 아래 화살표라서 맞춘다.
        tailBox.putClientProperty(FlatClientProperties.STYLE,
                "arrowType: chevron; buttonBackground: #00000000; buttonEditableBackground: #00000000;"
                        + " buttonSeparatorColor: #00000000; buttonArrowColor: " + Theme.hex(Theme.TEXT_SECONDARY)
                        + "; buttonDisabledArrowColor: " + Theme.hex(Theme.TEXT_MUTED));
        tailBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                return super.getListCellRendererComponent(list, "최근 " + value + "줄", index, selected, focus);
            }
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(levels);
        left.add(search);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(follow);
        right.add(tailBox);
        right.add(wrapButton);
        right.add(refresh);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(8, 14, 8, 12)));
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /** 켜고 끄는 작은 버튼 모양. 켜지면 따라가기는 초록, 줄바꿈은 파랑 계열로 바뀐다. */
    private static void styleToggle(JToggleButton b, boolean green) {
        String on = green ? Theme.hex(Theme.RUNNING_BADGE_BG) : Theme.hex(Theme.ACCENT_SOFT_BG);
        String onText = green ? Theme.hex(Theme.RUNNING_BADGE_TEXT) : Theme.hex(Theme.TEXT);
        b.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; background: #00000000; borderWidth: 1; borderColor: " + Theme.hex(Theme.SECONDARY_BUTTON_BORDER)
                        + "; selectedBackground: " + on + "; selectedForeground: " + onText
                        + (green ? "; selectedBorderColor: " + Theme.hex(Theme.RUNNING_BORDER) : "")
                        + "; font: -1");
        b.setFocusable(false);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(b.getPreferredSize().width + 10, CONTROL_HEIGHT));
    }

    private JPanel bodyArea() {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.TERMINAL_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        // 시안: 로그 본문 위아래에 8px 여백. 여백은 스크롤 창 바깥에 두어서, 표가 스크롤될 때도 가장자리에 남는다.
        JPanel scrollFrame = new JPanel(new BorderLayout());
        scrollFrame.setBackground(Theme.TERMINAL_BG);
        scrollFrame.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(8, 0, 8, 0)));
        scrollFrame.add(scroll, BorderLayout.CENTER);

        message.setForeground(Theme.TEXT_MUTED);
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.setBackground(Theme.TERMINAL_BG);
        messagePanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));
        messagePanel.add(message, BorderLayout.CENTER);

        body.setOpaque(false);
        body.add(scrollFrame, CARD_TABLE);
        body.add(messagePanel, CARD_MESSAGE);
        return body;
    }

    private JPanel footer() {
        command.setFont(Theme.monoFont(11.5f));
        command.setForeground(Theme.TEXT_MUTED);
        command.setIconTextGap(8);
        summary.setForeground(Theme.TEXT_MUTED);
        summary.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(Theme.TERMINAL_BAR_BG);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(7, 14, 7, 14)));
        row.add(command, BorderLayout.WEST);
        row.add(summary, BorderLayout.EAST);
        return row;
    }

    private void wireEvents() {
        levels.addSelectionListener(i -> applyFilters());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilters();
            }
        });
        wrapButton.addActionListener(e -> table.setWrap(wrapButton.isSelected()));
        // 줄 수를 바꾸면 그만큼 다시 읽어 온다(받은 줄 수가 달라지므로).
        tailBox.addActionListener(e -> reload());
        refresh.addActionListener(e -> reload());
        openWindow.addActionListener(e -> {
            if (current != null) {
                onOpenWindow.accept(current);
            }
        });
        follow.addActionListener(e -> {
            if (follow.isSelected()) {
                table.scrollToEnd();
            }
        });
    }

    // ── 쓰는 쪽(MainFrame 등)이 부르는 메서드 ───────────────────

    /** 로그를 새로 받아야 할 때 불린다: (컨테이너, 최근 몇 줄). 받아 온 텍스트는 {@link #showLogs}로 돌려준다. */
    public void onReload(BiConsumer<Container, Integer> listener) {
        this.onReload = listener;
    }

    /** [별도 창으로 열기]를 누르면 지금 보는 컨테이너와 함께 불린다. */
    public void onOpenWindow(Consumer<Container> listener) {
        this.onOpenWindow = listener;
    }

    /** 지금 보고 있는 컨테이너. 없으면 null. */
    public Container selected() {
        return current;
    }

    /** 최근 몇 줄을 받을지(콤보 상자에서 고른 값). */
    public int tail() {
        return (Integer) tailBox.getSelectedItem();
    }

    /**
     * 컨테이너 목록이 바뀌었을 때(주기적 조회) 전환 칩을 다시 만든다. 보고 있던 컨테이너가 아직 있으면
     * 선택을 유지하고(로그는 다시 받지 않는다), 사라졌으면 선택을 푼다.
     */
    public void setContainers(List<Container> list) {
        this.containers = List.copyOf(list);
        String keepId = current == null ? null : current.id();
        for (FilterChip chip : chips.values()) {
            chipGroup.remove(chip);
        }
        chips.clear();
        chipRow.removeAll();
        Container keep = null;
        for (Container c : this.containers) {
            FilterChip chip = new FilterChip(c.name());
            chip.setStatusDot(c.isRunning());
            // ActionListener는 사용자가 눌렀을 때만 불린다(setSelected로 바꿀 때는 불리지 않는다).
            chip.addActionListener(e -> select(c));
            chipGroup.add(chip);
            chipRow.add(chip);
            chips.put(c.id(), chip);
            if (c.id().equals(keepId)) {
                keep = c;
                chip.setSelected(true);
            }
        }
        current = keep;
        if (current == null && keepId != null) {
            all = List.of();
            showHint("컨테이너를 고르면 로그가 보여요");
            applyFilters();
        }
        updateFollow();
        chipRow.revalidate();
        chipRow.repaint();
    }

    /**
     * 아직 아무것도 고르지 않았으면 첫 컨테이너(목록은 실행 중인 것이 앞에 온다)를 골라 로그를 요청한다.
     * 이미 고른 것이 있거나 컨테이너가 없으면 아무것도 하지 않는다. 컨테이너 탭을 열었을 때 빈 화면 대신
     * 로그가 바로 보이게 하려는 것이다.
     */
    public void ensureSelection() {
        if (current == null && !containers.isEmpty()) {
            select(containers.getFirst());
        }
    }

    /** 컨테이너를 고르고 그 로그를 요청한다(칩을 눌렀을 때, 또는 표의 [로그] 버튼에서). */
    public void select(Container container) {
        current = container;
        FilterChip chip = chips.get(container.id());
        if (chip != null) {
            chip.setSelected(true);
        }
        // 멈춘 컨테이너는 따라갈 것이 없다: 따라가기를 끄고 막는다. 실행 중이면 켠다.
        follow.setSelected(container.isRunning());
        updateFollow();
        reload();
    }

    /** 받아 오는 중임을 보여 준다. */
    public void showLoading() {
        message.setForeground(Theme.TEXT_MUTED);
        message.setText("불러오는 중…");
        cards.show(body, CARD_MESSAGE);
    }

    /** 가운데에 안내 문구를 보여 준다(로그가 없을 때 등). */
    public void showHint(String text) {
        message.setForeground(Theme.TEXT_MUTED);
        message.setText(text);
        cards.show(body, CARD_MESSAGE);
    }

    /** 로그를 읽지 못했다는 오류를 붉은 글자로 보여 준다. */
    public void showError(String text) {
        message.setForeground(Theme.DANGER_TEXT);
        message.setText(text);
        cards.show(body, CARD_MESSAGE);
    }

    /**
     * 받아 온 로그 텍스트를 보여 준다. 그 사이 다른 컨테이너로 바뀌었으면(늦게 도착한 응답) 버린다.
     */
    public void showLogs(Container container, String output) {
        if (current == null || !current.id().equals(container.id())) {
            return;
        }
        all = LogLine.parseAll(output);
        if (all.isEmpty()) {
            showHint("(로그 없음)");
        } else {
            cards.show(body, CARD_TABLE);
        }
        applyFilters();
        if (follow.isSelected()) {
            table.scrollToEnd();
        }
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    /** 지금 표에 보이는 줄 수(거르기 뒤). */
    int visibleLines() {
        return table.lineCount();
    }

    /** 하단 막대 오른쪽의 집계 글자. */
    String summaryText() {
        return summary.getText();
    }

    // ── 안쪽 동작 ───────────────────────────────────────────────

    private void reload() {
        if (current == null) {
            return;
        }
        showLoading();
        showSummary();
        onReload.accept(current, tail());
    }

    private void updateFollow() {
        boolean running = current != null && current.isRunning();
        follow.setEnabled(running);
        if (!running) {
            follow.setSelected(false);
        }
    }

    /** 구간 선택(전체/경고/오류)과 검색어로 보여 줄 줄을 고르고, 개수와 하단 집계를 갱신한다. */
    private void applyFilters() {
        int warn = count(Level.WARN);
        int error = count(Level.ERROR);
        levels.setLabel(0, "전체 " + all.size());
        levels.setLabel(1, "경고 " + warn);
        levels.setLabel(2, "오류 " + error);

        int level = levels.getSelectedIndex();
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<LogLine> shown = all.stream()
                .filter(l -> level == 0 || (level == 1 && l.level() == Level.WARN) || (level == 2 && l.level() == Level.ERROR))
                .filter(l -> query.isEmpty() || l.message().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        table.setLines(shown);
        showSummary();
    }

    private int count(Level level) {
        int n = 0;
        for (LogLine l : all) {
            if (l.level() == level) {
                n++;
            }
        }
        return n;
    }

    /** 하단 막대: 실제로 실행하는 명령(컨테이너 이름으로 읽기 쉽게 적는다)과 줄 수 집계. */
    private void showSummary() {
        if (current == null) {
            command.setIcon(null);
            command.setText(" ");
            summary.setText(" ");
            return;
        }
        command.setIcon(dot(current.isRunning() ? Theme.RUNNING_DOT : Theme.TEXT_MUTED));
        // 실제 명령은 컨테이너 id로 실행하지만(DockerCommands.logs), 사람이 읽기 쉽게 이름으로 적는다.
        command.setText("docker logs --tail " + tail() + " --timestamps " + current.name());
        summary.setText("보이는 줄 " + table.lineCount() + " / 받은 줄 " + all.size()
                + " · 경고 " + count(Level.WARN) + " · 오류 " + count(Level.ERROR));
    }

    /** 지름 6의 작은 점 아이콘. */
    private static Icon dot(java.awt.Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(color);
                    g2.fillOval(x, y, 6, 6);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 6;
            }

            @Override
            public int getIconHeight() {
                return 6;
            }
        };
    }
}
