package com.infradesk.ui.overview;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.SavedCommand;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 개요 오른쪽 열의 "저장된 명령어" 카드 (디자인 v2): "누르면 터미널 탭에서 실행해요"와 명령어 줄들. 누르면 그
 * 명령어를 터미널 탭으로 보낸다(보내는 일은 쓰는 쪽이 한다). 명령어를 만들고 고치는 곳은 터미널 탭이다.
 */
public final class SavedCommandsCard extends RoundedPanel {

    /** 한 번에 보여 주는 명령어 수. 더 있으면 터미널 탭에서 본다. */
    static final int MAX_ROWS = 5;
    private static final Color NORMAL = new Color(0x1E1F22);
    private static final Color HOVER = new Color(0x26282B);

    private final JPanel list = new JPanel();
    private Consumer<String> onRun = command -> { };

    public SavedCommandsCard() {
        super(new BorderLayout(0, 10), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        setAlignmentX(LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        JLabel title = new JLabel("저장된 명령어");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        JLabel hint = new JLabel("누르면 터미널 탭에서 실행해요");
        hint.setForeground(Theme.TEXT_MUTED);
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        title.setAlignmentX(LEFT_ALIGNMENT);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        head.add(title);
        head.add(Box.createVerticalStrut(2));
        head.add(hint);
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        add(head, BorderLayout.NORTH);
        add(list, BorderLayout.CENTER);
        setCommands(List.of());
    }

    /** 명령어를 눌렀을 때: 그 명령어 글자가 넘어온다. */
    public void onRun(Consumer<String> listener) {
        this.onRun = listener;
    }

    public void setCommands(List<SavedCommand> commands) {
        list.removeAll();
        if (commands.isEmpty()) {
            JLabel empty = new JLabel("저장된 명령어가 없어요. 터미널 탭에서 추가할 수 있어요.");
            empty.setForeground(Theme.TEXT_MUTED);
            empty.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            empty.setAlignmentX(LEFT_ALIGNMENT);
            empty.setMinimumSize(new Dimension(0, empty.getPreferredSize().height));
            list.add(empty);
        }
        for (SavedCommand c : commands.stream().limit(MAX_ROWS).toList()) {
            list.add(row(c));
            list.add(Box.createVerticalStrut(8));
        }
        revalidate();
        repaint();
    }

    private JPanel row(SavedCommand c) {
        RoundedPanel row = new RoundedPanel(new BorderLayout(10, 0), NORMAL, Theme.DIVIDER, Theme.ARC_CARD) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(10, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
            }
        };
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setToolTipText(c.command());
        JLabel mark = new JLabel("▸");
        mark.setForeground(Theme.ACCENT_TEXT);
        mark.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        JLabel name = new JLabel(c.name());
        JLabel command = new JLabel(c.command());
        command.setFont(Theme.monoFont(11.5f));
        command.setForeground(Theme.TEXT_MUTED);
        name.setMinimumSize(new Dimension(0, name.getPreferredSize().height));
        command.setMinimumSize(new Dimension(0, command.getPreferredSize().height));
        JPanel text = new JPanel(new BorderLayout(0, 3));
        text.setOpaque(false);
        text.add(name, BorderLayout.NORTH);
        text.add(command, BorderLayout.CENTER);
        row.add(mark, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onRun.accept(c.command());
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                row.setBackground(HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                row.setBackground(NORMAL);
            }
        };
        for (java.awt.Component part : new java.awt.Component[] {row, text, name, command, mark}) {
            part.addMouseListener(click);
        }
        return row;
    }
}
