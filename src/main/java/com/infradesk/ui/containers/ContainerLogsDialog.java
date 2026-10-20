package com.infradesk.ui.containers;

import com.infradesk.core.Server;
import com.infradesk.service.ContainerService;
import com.infradesk.ssh.Container;
import com.infradesk.ui.Async;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.terminal.HostKeyDialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** 컨테이너 하나의 최근 로그 줄. 모달이 아니며 여러 개를 나란히 열 수 있다. */
public class ContainerLogsDialog extends JDialog {

    private final ContainerService service;
    private final Server server;
    private final Container container;
    private final JTextArea text = new JTextArea();
    private final JComboBox<Integer> tail = new JComboBox<>(new Integer[] {200, 1000, 5000});
    private final JLabel status = new JLabel(" ");
    private final JButton refresh = Buttons.secondary("새로고침", "refresh");

    public ContainerLogsDialog(Window owner, ContainerService service, Server server, Container container) {
        super(owner, "로그 · " + container.name() + " (" + server.name() + ")", ModalityType.MODELESS);
        this.service = service;
        this.server = server;
        this.container = container;
        text.setEditable(false);
        text.setFont(Theme.monoFont(12f));
        text.setBackground(Theme.TERMINAL_BG);
        text.setForeground(new Color(0xC9CCD1));
        text.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setOpaque(false);
        controls.add(new JLabel("최근"));
        controls.add(tail);
        controls.add(new JLabel("줄"));
        controls.add(refresh);
        status.setForeground(Theme.TEXT_MUTED);
        top.add(status, BorderLayout.WEST);
        top.add(controls, BorderLayout.EAST);

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        refresh.addActionListener(e -> load());
        tail.addActionListener(e -> load());
        setSize(900, 560);
        setLocationRelativeTo(owner);
        load();
    }

    private void load() {
        refresh.setEnabled(false);
        status.setText("불러오는 중…");
        int lines = (Integer) tail.getSelectedItem();
        Async.run(() -> service.logs(server, container, lines, new HostKeyDialog(this)), out -> {
            text.setText(out.isBlank() ? "(로그 없음)" : out);
            text.setCaretPosition(text.getDocument().getLength());
            status.setText(container.image() + " · " + container.shortId());
            refresh.setEnabled(true);
        }, err -> {
            status.setText(Async.message(err));
            status.setForeground(Theme.DANGER_TEXT);
            refresh.setEnabled(true);
        });
    }
}
