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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** 300px right panel of the terminal screen: saved commands, batch run, SFTP. */
final class TerminalSidePanel extends JPanel {

    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(60);

    private final TerminalService service;
    private final Supplier<List<Server>> servers;
    private final Consumer<String> sendToActive;
    private final JPanel commandList = new JPanel();
    private final JPanel serverChecks = new JPanel();
    private final List<JCheckBox> checks = new ArrayList<>();
    private final JTextField batchCommand = new JTextField("uptime");
    private final JButton runButton = Buttons.primary("실행", null);
    private final JButton sftpButton = Buttons.secondary("SFTP 파일", "folder");
    private List<SavedCommand> commands = List.of();

    TerminalSidePanel(TerminalService service, Supplier<List<Server>> servers, Consumer<String> sendToActive) {
        super(new BorderLayout());
        this.service = service;
        this.servers = servers;
        this.sendToActive = sendToActive;
        setPreferredSize(new Dimension(300, 0));
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

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        bottom.add(sftpButton);

        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        reloadCommands();
    }

    JButton sftpButton() {
        return sftpButton;
    }

    /** Rebuilds the batch checkboxes from the current server list; keeps previous choices. */
    void refreshServers() {
        List<String> checked = checks.stream().filter(JCheckBox::isSelected)
                .map(c -> (String) c.getClientProperty("serverId")).toList();
        boolean first = checks.isEmpty();
        serverChecks.removeAll();
        checks.clear();
        for (Server s : servers.get()) {
            boolean running = s.status() == ServerStatus.RUNNING;
            JCheckBox box = new JCheckBox(running ? s.name() : s.name() + " (" + s.status().label() + ")");
            box.putClientProperty("serverId", s.id());
            box.setOpaque(false);
            box.setEnabled(running);
            box.setSelected(running && (first || checked.contains(s.id())));
            box.addActionListener(e -> updateRunLabel());
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            checks.add(box);
            serverChecks.add(box);
        }
        serverChecks.revalidate();
        updateRunLabel();
    }

    private JPanel savedCommandsSection() {
        JPanel section = column();
        JLabel title = bold("저장된 명령어");
        JButton add = Buttons.icon("plus", "명령어 추가", 26);
        add.addActionListener(e -> CommandEditDialog.show(this, null).ifPresent(c -> {
            List<SavedCommand> updated = new ArrayList<>(commands);
            updated.add(c);
            persist(updated);
        }));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);
        header.add(add, BorderLayout.EAST);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        section.add(header);
        section.add(Box.createVerticalStrut(8));
        commandList.setOpaque(false);
        commandList.setLayout(new BoxLayout(commandList, BoxLayout.Y_AXIS));
        commandList.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(commandList);
        return section;
    }

    private JPanel batchSection() {
        JPanel section = column();
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(16, 0, 0, 0)));
        section.add(bold("일괄 실행"));
        section.add(Box.createVerticalStrut(6));
        JLabel hint = new JLabel("선택한 서버에 같은 명령어를 한 번에 보내요.");
        hint.setForeground(Theme.TEXT_MUTED);
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(hint);
        section.add(Box.createVerticalStrut(10));
        serverChecks.setOpaque(false);
        serverChecks.setLayout(new BoxLayout(serverChecks, BoxLayout.Y_AXIS));
        serverChecks.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(serverChecks);
        section.add(Box.createVerticalStrut(10));
        batchCommand.setFont(Theme.monoFont(12f));
        batchCommand.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22");
        batchCommand.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "명령어");
        batchCommand.getAccessibleContext().setAccessibleName("일괄 명령어");
        batchCommand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        batchCommand.setPreferredSize(new Dimension(10, 32));
        batchCommand.setAlignmentX(Component.LEFT_ALIGNMENT);
        batchCommand.addActionListener(e -> runBatch());
        section.add(batchCommand);
        section.add(Box.createVerticalStrut(10));
        runButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.BUTTON_HEIGHT));
        runButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        runButton.addActionListener(e -> runBatch());
        section.add(runButton);
        return section;
    }

    private void updateRunLabel() {
        long n = selectedServers().size();
        runButton.setText(n == 0 ? "서버를 선택하세요" : n + "대에 실행");
        runButton.setEnabled(n > 0);
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
        BatchResultDialog dialog = new BatchResultDialog(SwingUtilities.getWindowAncestor(this), command, targets);
        dialog.setVisible(true);
        HostKeyDialog prompt = new HostKeyDialog(this);
        for (Server s : targets) {
            Async.run(() -> service.run(s, command, prompt, BATCH_TIMEOUT),
                    (ExecResult r) -> dialog.setResult(s, r),
                    err -> dialog.setResult(s, ExecResult.failure(Async.message(err))));
        }
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
        if (commands.isEmpty()) {
            JLabel empty = new JLabel("+로 자주 쓰는 명령어를 저장하세요");
            empty.setForeground(Theme.TEXT_MUTED);
            empty.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            commandList.add(empty);
        }
        for (SavedCommand c : commands) {
            commandList.add(commandButton(c));
            commandList.add(Box.createVerticalStrut(8));
        }
        commandList.revalidate();
        commandList.repaint();
    }

    private JButton commandButton(SavedCommand c) {
        // Two labels instead of HTML so long commands end in "…" instead of widening the panel.
        JButton b = new JButton();
        b.setLayout(new BorderLayout(0, 2));
        JLabel name = new JLabel(c.name());
        JLabel cmd = new JLabel(c.command());
        cmd.setFont(Theme.monoFont(11f));
        cmd.setForeground(Theme.TEXT_MUTED);
        b.add(name, BorderLayout.NORTH);
        b.add(cmd, BorderLayout.CENTER);
        b.getAccessibleContext().setAccessibleName(c.name() + ": " + c.command());
        b.putClientProperty(FlatClientProperties.STYLE,
                "background: #1E1F22; borderColor: #43454A; borderWidth: 1; hoverBackground: #26282B; margin: 8,4,8,4");
        b.setToolTipText("클릭하면 열린 터미널에서 실행해요: " + c.command());
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        int height = b.getPreferredSize().height;
        b.setPreferredSize(new Dimension(10, height));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        b.addActionListener(e -> sendToActive.accept(c.command()));

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
        b.setComponentPopupMenu(menu);
        return b;
    }

    /** Column that always fits the viewport width, so nothing scrolls sideways. */
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
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
