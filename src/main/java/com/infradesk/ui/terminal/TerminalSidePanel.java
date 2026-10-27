package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ssh.SavedCommand;
import com.infradesk.ui.Async;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.WrapLayout;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 터미널 탭의 오른쪽 패널 (디자인 v2): 저장된 명령어(검색, 카드), 여러 서버에서 실행(서버 칩, 명령 입력, 결과 카드).
 *
 * <p>저장된 명령어를 누르면 열려 있는 터미널에 보내고, 일괄 실행은 고른 서버마다 명령 한 번을 실행해서 결과를 아래
 * 카드({@link BatchResultView})에 도착하는 대로 보여 준다. 파일은 서버 화면의 파일 탭에서 다루므로 이 패널에는
 * [SFTP 파일] 버튼이 없다.
 */
final class TerminalSidePanel extends JPanel {

    /** 패널 폭. */
    static final int WIDTH = 330;
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(60);

    private final TerminalService service;
    private final Supplier<List<Server>> servers;
    private final Consumer<String> sendToActive;
    private final JPanel commandList = new JPanel();
    private final JTextField search = new JTextField();
    private final JPanel serverChecks = new JPanel(new WrapLayout(java.awt.FlowLayout.LEFT, 8, 8, true));
    private final List<ServerChip> checks = new ArrayList<>();
    private final JTextField batchCommand = new JTextField("uptime");
    private final JButton runButton = Buttons.primary("실행", null);
    private final BatchResultView results = new BatchResultView();
    private List<SavedCommand> commands = List.of();
    /** 마지막으로 실행한 일괄 명령. 결과 카드의 [다시]가 같은 명령을 다시 보낸다. */
    private String lastBatchCommand = "";

    TerminalSidePanel(TerminalService service, Supplier<List<Server>> servers, Consumer<String> sendToActive) {
        super(new BorderLayout());
        this.service = service;
        this.servers = servers;
        this.sendToActive = sendToActive;
        setPreferredSize(new Dimension(WIDTH, 0));
        setBackground(Theme.PANEL_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(16, 16, 16, 16)));

        JPanel content = new WidthTrackingPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(savedCommandsSection());
        content.add(Box.createVerticalStrut(16));
        content.add(batchSection());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        results.onRetry(server -> execute(server, lastBatchCommand, new HostKeyDialog(this)));
        reloadCommands();
    }

    /** 현재 서버 목록으로 일괄 실행의 서버 칩을 다시 만든다. 이전 선택은 유지한다. */
    void refreshServers() {
        List<String> checked = checks.stream().filter(ServerChip::isSelected)
                .map(c -> (String) c.getClientProperty("serverId")).toList();
        boolean first = checks.isEmpty();
        serverChecks.removeAll();
        checks.clear();
        for (Server s : servers.get()) {
            boolean running = s.status() == ServerStatus.RUNNING;
            ServerChip chip = new ServerChip(running ? s.name() : s.name() + " (" + s.status().label() + ")");
            chip.putClientProperty("serverId", s.id());
            chip.setEnabled(running);
            chip.setSelected(running && (first || checked.contains(s.id())));
            chip.addActionListener(e -> updateRunLabel());
            checks.add(chip);
            serverChecks.add(chip);
        }
        serverChecks.revalidate();
        serverChecks.repaint();
        updateRunLabel();
    }

    // ── 저장된 명령어 ───────────────────────────────────────────

    private JPanel savedCommandsSection() {
        JPanel section = column();
        JButton add = Buttons.ghost("＋ 추가");
        add.setToolTipText("명령어 추가");
        add.addActionListener(e -> CommandEditDialog.show(this, null).ifPresent(c -> {
            List<SavedCommand> updated = new ArrayList<>(commands);
            updated.add(c);
            persist(updated);
        }));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(bold("저장된 명령어"), BorderLayout.WEST);
        header.add(add, BorderLayout.EAST);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        section.add(header);
        section.add(Box.createVerticalStrut(10));

        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "명령어 찾기");
        search.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22");
        search.getAccessibleContext().setAccessibleName("저장된 명령어 찾기");
        search.setAlignmentX(Component.LEFT_ALIGNMENT);
        search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        search.setPreferredSize(new Dimension(10, 32));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuildCommands();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuildCommands();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuildCommands();
            }
        });
        section.add(search);
        section.add(Box.createVerticalStrut(10));
        commandList.setOpaque(false);
        commandList.setLayout(new BoxLayout(commandList, BoxLayout.Y_AXIS));
        commandList.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(commandList);
        return section;
    }

    /** 이름이나 명령어에 찾는 말이 들어 있는 것만. 찾는 말이 비어 있으면 전부(대소문자는 가리지 않는다). */
    static List<SavedCommand> filter(List<SavedCommand> all, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return all;
        }
        return all.stream().filter(c -> c.name().toLowerCase(Locale.ROOT).contains(q)
                || c.command().toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    private void reloadCommands() {
        Async.run(service::savedCommands, loaded -> {
            commands = loaded;
            rebuildCommands();
        }, err -> JOptionPane.showMessageDialog(this, Async.message(err), "저장된 명령어", JOptionPane.WARNING_MESSAGE));
    }

    private void persist(List<SavedCommand> updated) {
        commands = List.copyOf(updated);
        rebuildCommands();
        Async.run(() -> {
            service.saveCommands(updated);
            return null;
        }, ignored -> { }, err -> JOptionPane.showMessageDialog(this, Async.message(err), "저장된 명령어",
                JOptionPane.WARNING_MESSAGE));
    }

    private void rebuildCommands() {
        commandList.removeAll();
        List<SavedCommand> shown = filter(commands, search.getText());
        if (shown.isEmpty()) {
            JLabel empty = new JLabel(commands.isEmpty() ? "＋ 추가로 자주 쓰는 명령어를 저장하세요" : "일치하는 명령어가 없어요");
            empty.setForeground(Theme.TEXT_MUTED);
            empty.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            commandList.add(empty);
        }
        for (SavedCommand c : shown) {
            commandList.add(card(c));
            commandList.add(Box.createVerticalStrut(8));
        }
        commandList.revalidate();
        commandList.repaint();
    }

    private SavedCommandCard card(SavedCommand c) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("편집…");
        edit.addActionListener(e -> CommandEditDialog.show(this, c).ifPresent(updated ->
                persist(commands.stream().map(x -> x.id().equals(c.id()) ? updated : x).toList())));
        JMenuItem delete = new JMenuItem("삭제…");
        delete.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "'" + c.name() + "' 명령어를 삭제할까요?", "명령어 삭제",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                persist(commands.stream().filter(x -> !x.id().equals(c.id())).toList());
            }
        });
        menu.add(edit);
        menu.add(delete);
        SavedCommandCard card = new SavedCommandCard(c, () -> sendToActive.accept(c.command()), menu);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    // ── 여러 서버에서 실행 ──────────────────────────────────────

    private JPanel batchSection() {
        JPanel section = column();
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(16, 0, 0, 0)));
        section.add(bold("여러 서버에서 실행"));
        section.add(Box.createVerticalStrut(6));
        JLabel hint = new JLabel("같은 명령을 골라 둔 서버에 한 번에 보내요");
        hint.setForeground(Theme.TEXT_MUTED);
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(hint);
        section.add(Box.createVerticalStrut(6));
        serverChecks.setOpaque(false);
        serverChecks.setAlignmentX(Component.LEFT_ALIGNMENT);
        // 칩이 줄을 넘기면 높이가 늘도록 WrapLayout이 알려 준다.
        section.add(serverChecks);
        section.add(Box.createVerticalStrut(6));

        batchCommand.setFont(Theme.monoFont(12.5f));
        batchCommand.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22");
        batchCommand.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "명령어");
        batchCommand.getAccessibleContext().setAccessibleName("일괄 명령어");
        batchCommand.setPreferredSize(new Dimension(10, Theme.BUTTON_HEIGHT));
        batchCommand.addActionListener(e -> runBatch());
        runButton.addActionListener(e -> runBatch());
        JPanel input = new JPanel(new BorderLayout(8, 0));
        input.setOpaque(false);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.BUTTON_HEIGHT));
        input.add(batchCommand, BorderLayout.CENTER);
        input.add(runButton, BorderLayout.EAST);
        section.add(input);
        section.add(Box.createVerticalStrut(12));
        results.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(results);
        return section;
    }

    /** 실행 버튼 글자: "3대에 실행". 고른 서버가 없으면 비활성. */
    static String runLabel(int selected) {
        return selected == 0 ? "실행" : selected + "대에 실행";
    }

    private void updateRunLabel() {
        int n = selectedServers().size();
        runButton.setText(runLabel(n));
        runButton.setEnabled(n > 0);
        runButton.setPreferredSize(null);
        runButton.setPreferredSize(new Dimension(runButton.getPreferredSize().width + 8, Theme.BUTTON_HEIGHT));
        runButton.revalidate();
    }

    private List<Server> selectedServers() {
        List<String> ids = checks.stream().filter(c -> c.isSelected() && c.isEnabled())
                .map(c -> (String) c.getClientProperty("serverId")).toList();
        return servers.get().stream().filter(s -> ids.contains(s.id())).toList();
    }

    private void runBatch() {
        String command = batchCommand.getText().strip();
        List<Server> targets = selectedServers();
        if (command.isEmpty() || targets.isEmpty()) {
            return;
        }
        String names = String.join(", ", targets.stream().map(Server::name).toList());
        Object[] options = {targets.size() + "대에 실행", "취소"};
        int choice = JOptionPane.showOptionDialog(this,
                "이 명령어를 " + targets.size() + "대에서 실행할까요?\n\n$ " + command + "\n\n대상: " + names,
                "일괄 실행", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) {
            return;
        }
        lastBatchCommand = command;
        results.start(command, targets);
        HostKeyDialog prompt = new HostKeyDialog(this);
        for (Server s : targets) {
            execute(s, command, prompt);
        }
    }

    /** 서버 한 대에서 명령을 실행하고, 끝나면 걸린 시간과 함께 결과 카드에 알린다. */
    private void execute(Server server, String command, HostKeyDialog prompt) {
        long started = System.nanoTime();
        Async.run(() -> service.run(server, command, prompt, BATCH_TIMEOUT),
                (ExecResult r) -> results.setResult(server, r, elapsedMillis(started)),
                err -> results.setResult(server, ExecResult.failure(Async.message(err)), elapsedMillis(started)));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    // ── 공통 ────────────────────────────────────────────────────

    /** 항상 뷰포트 너비에 맞는 열. 가로로 스크롤되는 것이 없게 한다. */
    private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
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
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }
}
