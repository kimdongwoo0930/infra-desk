package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.SavedCommand;
import com.infradesk.ui.Async;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
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
 * 터미널 탭의 오른쪽 패널 (디자인 v2): 저장된 명령어(검색, 카드). 카드를 누르면 열려 있는 터미널에 그 명령어를
 * 보낸다. 파일은 서버 화면의 파일 탭에서 다루므로 이 패널에는 [SFTP 파일] 버튼이 없고, 여러 서버에 한 번에
 * 명령을 보내는 일괄 실행은 쓰는 일이 적어서 없앴다.
 */
final class TerminalSidePanel extends JPanel {

    /** 패널 폭. */
    static final int WIDTH = 330;
    private final TerminalService service;
    private final Consumer<String> sendToActive;
    private final JPanel commandList = new JPanel();
    private final JTextField search = new JTextField();
    private List<SavedCommand> commands = List.of();
    TerminalSidePanel(TerminalService service, Consumer<String> sendToActive) {
        super(new BorderLayout());
        this.service = service;
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

        JScrollPane scroll = new JScrollPane(content);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        reloadCommands();
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
