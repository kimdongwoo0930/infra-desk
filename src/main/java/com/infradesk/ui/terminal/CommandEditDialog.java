package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.SavedCommand;
import com.infradesk.ui.Theme;

import java.awt.Component;
import java.awt.Dimension;
import java.util.Optional;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** 저장된 명령어를 추가하거나 편집하는 작은 폼. */
final class CommandEditDialog {

    private CommandEditDialog() {
    }

    /** @param existing 편집할 명령어. 새로 추가하는 경우는 null */
    static Optional<SavedCommand> show(Component parent, SavedCommand existing) {
        JTextField name = new JTextField(existing == null ? "" : existing.name(), 28);
        JTextField command = new JTextField(existing == null ? "" : existing.command(), 28);
        name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "예: 봇 재시작");
        command.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "예: docker restart bot-app");
        command.setFont(Theme.monoFont(12.5f));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(label("이름", name));
        form.add(name);
        form.add(Box.createVerticalStrut(10));
        form.add(label("명령어", command));
        form.add(command);
        for (Component c : form.getComponents()) {
            if (c instanceof JTextField f) {
                f.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.BUTTON_HEIGHT));
            }
            ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        while (true) {
            int choice = JOptionPane.showConfirmDialog(parent, form, existing == null ? "명령어 추가" : "명령어 편집",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return Optional.empty();
            }
            String n = name.getText().strip();
            String c = command.getText().strip();
            if (!n.isEmpty() && !c.isEmpty()) {
                return Optional.of(new SavedCommand(existing == null ? UUID.randomUUID().toString() : existing.id(), n, c));
            }
            JOptionPane.showMessageDialog(parent, "이름과 명령어를 모두 입력하세요.", "명령어", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static JLabel label(String text, JTextField field) {
        JLabel l = new JLabel(text);
        l.setLabelFor(field);
        l.setForeground(Theme.TEXT_SECONDARY);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return l;
    }
}
