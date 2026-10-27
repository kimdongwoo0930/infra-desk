package com.infradesk.ui.settings;

import com.infradesk.service.RefreshPolicy;
import com.infradesk.ssh.OpenConnection;
import com.infradesk.ssh.Purpose;
import com.infradesk.ui.ConnectionSummary;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.metrics.LiveController;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 설정 "연결·조회": 얼마나 자주 묻고, SSH 연결을 어떻게 유지할지. 이 값들은 지금 코드에 고정되어 있어서 바꿀 수 있는
 * 설정이 아니라 현재 값을 글자로 보여 준다(주기를 바꾸면 PRD·README도 함께 고쳐야 하고 OCI 요청 제한과도 얽혀 있어
 * 따로 상의한다). "지금 열린 SSH 연결" 표는 풀이 열어 둔 연결을 보여 주고, 연결을 하나씩 또는 한꺼번에 끊을 수
 * 있다. 놀고 있는 연결은 바로 끊고, 쓰는 중인 연결(전송 중이거나 파일 탭이 쥐고 있는 것)은 확인을 받은 뒤에
 * 강제로 끊는다. 터미널 탭은 풀 밖이라 이 표와 상관없다.
 */
public final class ConnectionPage extends SettingsPage {

    public static final String ID = "connection";

    /** 메트릭 조회 주기. 모니터링 탭과 사이드바 CPU가 이 간격으로 읽는다(MainFrame의 메트릭 타이머와 같다). */
    static final Duration METRICS_INTERVAL = Duration.ofMinutes(1);
    /** 풀이 오래 열린 연결을 새로 맺는 간격(docs/architecture/ssh.md "수명 12시간"). */
    static final Duration CONNECTION_LIFETIME = Duration.ofHours(12);

    public ConnectionPage() {
        this(Source.none());
    }

    public ConnectionPage(Source source) {
        super(ID, "연결·조회", "연결·조회", "얼마나 자주 묻고, SSH 연결을 어떻게 유지할지");
        this.source = source;

        SettingsCard card = new SettingsCard();
        card.add(new SettingsRow("서버 상태 새로고침",
                "OCI API 요청 제한 때문에 " + Controls.duration(RefreshPolicy.NORMAL_INTERVAL)
                        + "마다 읽어요 · 시작·정지 직후에는 " + Controls.duration(RefreshPolicy.FAST_INTERVAL) + " 간격",
                Controls.value(Controls.duration(RefreshPolicy.NORMAL_INTERVAL))));
        card.add(new SettingsRow("메트릭", "OCI는 1분 단위로 모아요 · 직접 연결 서버는 SSH로 1분마다 읽어요",
                Controls.value(Controls.duration(METRICS_INTERVAL) + "마다")));
        card.add(new SettingsRow("실시간 모드 자동 꺼짐", "켜 둔 걸 잊어도 SSH로 계속 읽지 않게",
                Controls.value(Controls.duration(LiveController.AUTO_OFF) + " 뒤")));
        card.add(new SettingsRow("창이 메뉴 막대로 숨으면", "사이드바·메뉴 막대 CPU만 계속 보여줘요",
                Controls.value("조회 연결만 남기기")));
        card.add(new SettingsRow("SSH 연결 새로 맺기", "오래 열린 연결은 쓰지 않는 틈에 다시 맺어요 · 쓰지 않는 연결은 5~15분 뒤 닫아요",
                Controls.value(Controls.duration(CONNECTION_LIFETIME) + "마다")));
        addCard(card);
        addCard(openConnections());
    }

    /**
     * 설정 화면이 이 항목에 알려 주는 것: 풀이 열어 둔 연결 목록, 서버 id → 이름, 열려 있는 터미널 탭 수, 그리고
     * 연결을 끊는 동작. 앞의 셋은 이미 메모리에 있는 값을 읽을 뿐이라 서버에 접속하지 않는다.
     *
     * @param disconnector 연결을 끊는 동작
     */
    public record Source(java.util.function.Supplier<List<OpenConnection>> connections,
                         java.util.function.Supplier<java.util.Map<String, String>> serverNames,
                         java.util.function.IntSupplier terminalTabs,
                         Disconnector disconnector) {
        static Source none() {
            return new Source(List::of, java.util.Map::of, () -> 0, Disconnector.NONE);
        }
    }

    /** 풀의 연결을 끊는 동작. {@code force}가 아니면 쓰는 중인 연결은 건드리지 않는다. */
    public interface Disconnector {
        Disconnector NONE = new Disconnector() {
            @Override
            public boolean disconnect(String serverId, Purpose purpose, boolean force) {
                return false;
            }

            @Override
            public int disconnectAll(boolean force) {
                return 0;
            }
        };

        /** @return 끊었으면 true */
        boolean disconnect(String serverId, Purpose purpose, boolean force);

        /** @return 끊은 연결 수 */
        int disconnectAll(boolean force);
    }

    private final Source source;
    private final JPanel table = new JPanel(new java.awt.GridBagLayout());
    private final JLabel summary = new JLabel(" ");
    private final JLabel note = new JLabel(" ");
    /** 끊은 결과 한 줄("연결 2개를 끊었어요"). 잠깐 보였다가 지운다. */
    private final JLabel result = new JLabel(" ");
    private Instant resultAt = Instant.EPOCH;
    /** 쓰는 중인 연결을 강제로 끊어도 되는지 묻는다(시험에서는 바꿔 끼운다). */
    private java.util.function.Predicate<String> confirmer = this::askYesNo;
    /** 쓰는 중인 연결이 있을 때 [모든 연결 끊기]를 어떻게 할지 묻는다: 0 모두, 1 놀고 있는 것만, 그 밖에는 취소. */
    private java.util.function.IntUnaryOperator closeAllChooser = this::askCloseAll;
    private static final Duration RESULT_SHOWN = Duration.ofSeconds(8);

    /** "지금 열린 SSH 연결" 표: 서버 × 용도. 서버마다 어느 용도의 연결이 열려 있는지와 얼마나 되었는지를 보여 준다. */
    private JPanel openConnections() {
        SettingsCard card = new SettingsCard();

        JPanel head = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(16, 24, 12, 24));
        JLabel title = new JLabel("지금 열린 SSH 연결");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        summary.setForeground(Theme.TEXT_MUTED);
        head.add(title);
        head.add(javax.swing.Box.createHorizontalStrut(10));
        head.add(summary);
        card.add(head);

        table.setOpaque(false);
        table.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 24));
        card.add(table);

        note.setForeground(Theme.TEXT_MUTED);
        note.setMinimumSize(new Dimension(0, note.getPreferredSize().height));
        JButton closeAll = Buttons.danger("모든 연결 끊기", null);
        closeAll.setToolTipText("열린 연결을 모두 끊어요. 쓰는 중인 연결은 끊기 전에 물어봐요. 터미널 탭은 그대로예요. 필요하면 다시 맺어요.");
        closeAll.addActionListener(e -> disconnectAll());
        result.setForeground(Theme.ACCENT_TEXT);
        result.setAlignmentX(LEFT_ALIGNMENT);
        note.setAlignmentX(LEFT_ALIGNMENT);
        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        texts.add(result);
        texts.add(note);
        JPanel foot = new JPanel(new BorderLayout(16, 0));
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createEmptyBorder(14, 24, 14, 24));
        foot.add(texts, BorderLayout.CENTER);
        foot.add(closeAll, BorderLayout.EAST);
        card.add(foot);
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.add(card);
        refresh();
        return wrap;
    }

    /** 열린 연결 표를 지금 상태로 다시 그린다. 이 항목이 보일 때 주기적으로 불린다. */
    public void refresh() {
        List<OpenConnection> connections = source.connections().get();
        java.util.Map<String, String> names = source.serverNames().get();
        summary.setText("서버마다 용도별로 하나씩 · 연결 " + connections.size() + "개");
        int tabs = source.terminalTabs().getAsInt();
        note.setText((tabs > 0 ? "터미널 탭 " + tabs + "개는 이 표와 따로 연결돼요. " : "터미널 탭은 이 표와 따로 연결돼요. ")
                + "서버를 바꾸는 명령(명령 연결)은 실패해도 저절로 다시 보내지 않아요.");

        if (!result.getText().isBlank() && Duration.between(resultAt, Instant.now()).compareTo(RESULT_SHOWN) > 0) {
            result.setText(" ");
        }
        table.removeAll();
        addHeader();
        // 연결이 있는 서버를 앞에, 없는 서버를 뒤에(각각 이름순).
        List<String> ids = new java.util.ArrayList<>(names.keySet());
        for (OpenConnection c : connections) {
            if (!ids.contains(c.serverId())) {
                ids.add(c.serverId());   // 목록에 없는 서버(지운 서버 등)의 연결도 숨기지 않는다
            }
        }
        ids.sort(java.util.Comparator
                .comparing((String id) -> connections.stream().noneMatch(c -> c.serverId().equals(id)))
                .thenComparing(id -> names.getOrDefault(id, id)));
        Instant now = Instant.now();
        int row = 1;
        for (String id : ids) {
            List<OpenConnection> mine = connections.stream().filter(c -> c.serverId().equals(id)).toList();
            addRow(row++, names.getOrDefault(id, id), mine, ConnectionSummary.rowStatus(mine, now));
        }
        if (ids.isEmpty()) {
            JLabel empty = new JLabel("서버가 없어요.");
            empty.setForeground(Theme.TEXT_MUTED);
            java.awt.GridBagConstraints c = cell(0, 1, 6, 1);
            c.insets = new java.awt.Insets(14, 0, 14, 0);
            table.add(empty, c);
        }
        table.revalidate();
        table.repaint();
    }

    private static java.awt.GridBagConstraints cell(int x, int y, int width, double weight) {
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = width;
        c.weightx = weight;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.anchor = java.awt.GridBagConstraints.WEST;
        return c;
    }

    private void addHeader() {
        String[] names = {"서버", "조회", "명령", "파일", "전송", "상태"};
        double[] weights = {3, 1, 1, 1, 1, 4};
        for (int i = 0; i < names.length; i++) {
            JLabel l = new JLabel(names[i], i >= 1 && i <= 4 ? javax.swing.SwingConstants.CENTER : javax.swing.SwingConstants.LEFT);
            l.setForeground(Theme.TEXT_MUTED);
            l.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: -1");
            java.awt.GridBagConstraints c = cell(i, 0, 1, weights[i]);
            c.insets = new java.awt.Insets(8, 0, 8, 8);
            table.add(l, c);
        }
    }

    private void addRow(int y, String name, List<OpenConnection> mine, String status) {
        double[] weights = {3, 1, 1, 1, 1, 4};
        JLabel serverName = new JLabel(name);
        serverName.setFont(serverName.getFont().deriveFont(java.awt.Font.BOLD));
        java.awt.GridBagConstraints c0 = cell(0, y, 1, weights[0]);
        c0.insets = new java.awt.Insets(10, 0, 10, 8);
        table.add(serverName, c0);
        Purpose[] order = {Purpose.QUERY, Purpose.COMMAND, Purpose.FILES, Purpose.TRANSFER};
        for (int i = 0; i < order.length; i++) {
            Purpose purpose = order[i];
            OpenConnection open = mine.stream().filter(c -> c.purpose() == purpose).findFirst().orElse(null);
            JLabel mark = new JLabel(open != null ? "●" : "—", javax.swing.SwingConstants.CENTER);
            mark.setForeground(open != null ? Theme.RUNNING_DOT : Theme.DIVIDER);
            // 색만으로 구분하지 않는다: 열린 연결은 점, 없는 칸은 줄표.
            mark.setToolTipText(ConnectionSummary.label(purpose) + (open != null ? " 연결이 열려 있어요" : " 연결 없음"));
            java.awt.GridBagConstraints c = cell(i + 1, y, 1, weights[i + 1]);
            c.insets = new java.awt.Insets(open != null ? 4 : 10, 0, open != null ? 4 : 10, 8);
            if (open == null) {
                table.add(mark, c);
                continue;
            }
            // 열린 연결: 점 옆에 [끊기] 버튼. 쓰는 중이면 끊을 수 없다.
            JPanel cellPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 4, 0));
            cellPanel.setOpaque(false);
            cellPanel.add(mark);
            cellPanel.add(disconnectButton(name, purpose, open));
            table.add(cellPanel, c);
        }
        JLabel state = new JLabel(status);
        state.setForeground(mine.isEmpty() ? Theme.TEXT_MUTED : Theme.TEXT);
        java.awt.GridBagConstraints c5 = cell(5, y, 1, weights[5]);
        c5.insets = new java.awt.Insets(10, 0, 10, 0);
        table.add(state, c5);
    }

    /** 연결 하나를 끊는 작은 [×] 버튼. 쓰는 중(전송, 실행 중인 명령 등)이면 비활성이다. */
    private JButton disconnectButton(String serverName, Purpose purpose, OpenConnection connection) {
        String what = serverName + "의 " + ConnectionSummary.label(purpose) + " 연결";
        JButton b = Buttons.icon("x", what + " 끊기", 22);
        b.setName("disconnect-" + connection.serverId() + "-" + purpose);
        boolean busy = connection.active() > 0;
        if (busy) {
            b.setToolTipText(what + "은 쓰는 중이에요. 누르면 확인한 뒤 강제로 끊어요.");
        }
        b.addActionListener(e -> {
            // 쓰는 중인 연결은 확인을 받은 뒤에만 강제로 끊는다. 그 사이에 쓰기가 끝났을 수도 있어서 지금 상태를 다시 본다.
            boolean busyNow = source.connections().get().stream().anyMatch(c ->
                    c.serverId().equals(connection.serverId()) && c.purpose() == purpose && c.active() > 0);
            if (busyNow && !confirmer.test(what + "은 지금 쓰는 중이에요. 전송 중인 파일이 있으면 실패하고, 파일 탭은 다시 연결해요.\n"
                    + "그래도 끊을까요?")) {
                return;
            }
            boolean closed = source.disconnector().disconnect(connection.serverId(), purpose, busyNow);
            showResult(closed ? what + "을 끊었어요" : what + "은 끊지 못했어요. 잠시 뒤 다시 눌러 보세요");
            refresh();
        });
        return b;
    }

    /** [모든 연결 끊기]: 쓰는 중인 연결이 있으면 함께 끊을지 묻는다. */
    private void disconnectAll() {
        long busyBefore = source.connections().get().stream().filter(c -> c.active() > 0).count();
        boolean force = false;
        if (busyBefore > 0) {
            int choice = closeAllChooser.applyAsInt((int) busyBefore);
            if (choice != 0 && choice != 1) {
                return;   // 취소
            }
            force = choice == 0;
        }
        int closed = source.disconnector().disconnectAll(force);
        long busyLeft = force ? 0 : source.connections().get().stream().filter(c -> c.active() > 0).count();
        showResult(closeAllMessage(closed, busyLeft));
        refresh();
    }

    private boolean askYesNo(String message) {
        return javax.swing.JOptionPane.showConfirmDialog(this, message, "연결 끊기",
                javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE) == javax.swing.JOptionPane.YES_OPTION;
    }

    private int askCloseAll(int busy) {
        String[] options = {"쓰는 중인 것도 모두 끊기", "놀고 있는 것만 끊기", "취소"};
        return javax.swing.JOptionPane.showOptionDialog(this,
                "쓰는 중인 연결이 " + busy + "개 있어요. 전송 중인 파일이 있으면 실패하고, 파일 탭은 다시 연결해요.",
                "모든 연결 끊기", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
    }

    /** 모두 끊은 결과 문구. */
    static String closeAllMessage(int closed, long busy) {
        String text = closed == 0 ? "끊을 연결이 없어요" : "연결 " + closed + "개를 끊었어요";
        return busy > 0 ? text + " · 쓰는 중인 " + busy + "개는 그대로 뒀어요" : text;
    }

    private void showResult(String text) {
        result.setText(text);
        resultAt = Instant.now();
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    String summaryText() {
        return summary.getText();
    }

    int tableRows() {
        return table.getComponentCount() / 6;
    }

    String resultText() {
        return result.getText();
    }

    /** 시험용: 확인 창 대신 쓸 답. */
    void answerWith(java.util.function.Predicate<String> confirmer, java.util.function.IntUnaryOperator closeAllChooser) {
        this.confirmer = confirmer;
        this.closeAllChooser = closeAllChooser;
    }

    /** 표 안의 이름으로 부품을 찾는다(없으면 null). */
    JButton disconnectButton(String serverId, Purpose purpose) {
        return find(table, "disconnect-" + serverId + "-" + purpose);
    }

    JButton closeAllButton() {
        return find(this, null);
    }

    private static JButton find(java.awt.Container root, String name) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof JButton b && (name == null ? "모든 연결 끊기".equals(b.getText()) : name.equals(b.getName()))) {
                return b;
            }
            if (c instanceof java.awt.Container inner) {
                JButton found = find(inner, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
