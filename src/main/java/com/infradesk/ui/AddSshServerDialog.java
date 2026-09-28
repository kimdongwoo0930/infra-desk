package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.ServerStatus;
import com.infradesk.core.SshHostProperties;
import com.infradesk.service.InventoryService;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.WrappingLabel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

/**
 * Adds (or edits) a machine reached directly over SSH, such as a home server or a Mac mini. Only
 * the name and address live here; the SSH user and key are set in the usual SSH settings dialog,
 * which opens right after saving a new one.
 */
public class AddSshServerDialog extends JDialog {

    private final InventoryService service;
    private final Account existing;
    private final JTextField name = field(false, "예: 맥미니");
    private final JTextField host = field(true, "예: mac-mini, 192.168.0.20, 100.64.0.5");
    private final JTextField port = field(true, "22");
    private final WrappingLabel status = new WrappingLabel(" ", 436, -1, Theme.TEXT_SECONDARY);
    private final JButton testButton = Buttons.secondary("연결 확인", null);
    private final JButton saveButton = Buttons.primary("저장", null);
    private Account result;

    /** @param existing the account to edit, or null to add one */
    public AddSshServerDialog(Window owner, InventoryService service, Account existing) {
        super(owner, existing == null ? "직접 연결 서버 추가" : "서버 설정 · " + existing.displayName(),
                ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.existing = existing;
        port.setText("22");
        if (existing != null) {
            name.setText(existing.displayName());
            host.setText(SshHostProperties.host(existing));
            port.setText(Integer.toString(SshHostProperties.port(existing)));
        }
        name.getAccessibleContext().setAccessibleName("표시 이름");
        host.getAccessibleContext().setAccessibleName("주소");
        port.getAccessibleContext().setAccessibleName("SSH 포트");

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(saveButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setSize(480, getHeight());
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; the saved account, or empty when cancelled. */
    public Optional<Account> showDialog() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    private JPanel body() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));
        add(body, new WrappingLabel("클라우드 계정 없이 SSH로 바로 접속하는 컴퓨터예요. 집 서버, 맥미니, 사무실 PC 등. "
                + "터미널·SFTP·Docker·실시간 모니터링을 쓸 수 있고, 켜고 끄기는 지원하지 않아요.", 436, -1, Theme.TEXT_SECONDARY));
        body.add(Box.createVerticalStrut(16));
        add(body, labeled("표시 이름", name));
        body.add(Box.createVerticalStrut(14));
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(labeled("주소 (호스트 이름 또는 IP)", host), BorderLayout.CENTER);
        port.setPreferredSize(new Dimension(80, Theme.BUTTON_HEIGHT));
        row.add(labeled("SSH 포트", port), BorderLayout.EAST);
        add(body, row);
        body.add(Box.createVerticalStrut(8));
        add(body, new WrappingLabel("집 밖에서도 쓰려면 Tailscale 같은 사설 네트워크 주소를 추천해요. "
                + "공유기에서 SSH 포트를 인터넷에 여는 건 권하지 않아요.", 436, -1, Theme.TEXT_MUTED));
        body.add(Box.createVerticalStrut(12));
        add(body, status);
        return body;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new java.awt.Color(0x26282B));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)));
        JButton cancel = Buttons.ghost("취소");
        cancel.addActionListener(e -> dispose());
        testButton.addActionListener(e -> test());
        saveButton.addActionListener(e -> save());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(cancel);
        right.add(saveButton);
        footer.add(testButton, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    /** The account from the fields, or empty after showing what's wrong. */
    private Optional<Account> build() {
        String n = name.getText().strip();
        String h = host.getText().strip();
        if (n.isEmpty()) {
            showStatus("표시 이름을 입력하세요.", true);
            return Optional.empty();
        }
        if (!SshHostProperties.isValidHost(h)) {
            showStatus("주소는 호스트 이름이나 IP만 입력하세요 (예: mac-mini, 192.168.0.20).", true);
            return Optional.empty();
        }
        int p;
        try {
            p = Integer.parseInt(port.getText().strip());
        } catch (NumberFormatException e) {
            p = -1;
        }
        if (p < 1 || p > 65535) {
            showStatus("포트는 1~65535 사이 숫자예요.", true);
            return Optional.empty();
        }
        String id = existing != null ? existing.id() : "ssh-" + UUID.randomUUID();
        return Optional.of(new Account(id, n, ProviderType.SSH, SshHostProperties.REGION, SshHostProperties.of(h, p)));
    }

    private void test() {
        Optional<Account> account = build();
        if (account.isEmpty()) {
            return;
        }
        setBusy(true, "확인하는 중…");
        Async.run(() -> service.preview(account.get(), Map.of()), servers -> {
            setBusy(false, null);
            boolean up = !servers.isEmpty() && servers.getFirst().status() == ServerStatus.RUNNING;
            showStatus(up ? "SSH 포트가 응답해요. 저장한 뒤 SSH 키를 등록하세요."
                    : "응답이 없어요. 컴퓨터가 켜져 있는지, 원격 로그인(SSH)이 켜져 있는지, 주소와 포트를 확인하세요.", !up);
            if (up) {
                status.setForeground(Theme.RUNNING_BADGE_TEXT);
            }
        }, err -> {
            setBusy(false, null);
            showStatus(Async.message(err), true);
        });
    }

    private void save() {
        Optional<Account> account = build();
        if (account.isEmpty()) {
            return;
        }
        setBusy(true, "저장하는 중…");
        Async.run(() -> {
            if (existing != null) {
                service.updateAccount(account.get(), Map.of());
            } else {
                service.addAccount(account.get(), Map.of());
            }
            return account.get();
        }, saved -> {
            result = saved;
            dispose();
        }, err -> {
            setBusy(false, null);
            showStatus(Async.message(err), true);
        });
    }

    private void setBusy(boolean busy, String message) {
        testButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
        if (message != null) {
            showStatus(message, false);
        }
    }

    private void showStatus(String message, boolean error) {
        status.setText(message);
        status.setForeground(error ? Theme.DANGER_TEXT : Theme.TEXT_SECONDARY);
    }

    private static void add(JPanel body, JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(c);
    }

    private static JPanel labeled(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setForeground(Theme.TEXT_SECONDARY);
        l.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        l.setLabelFor(field);
        p.add(l, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private static JTextField field(boolean mono, String placeholder) {
        JTextField f = new JTextField();
        f.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        f.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22; margin: 0,4,0,4");
        f.setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
        if (mono) {
            f.setFont(Theme.monoFont(12f));
            f.enableInputMethods(false);
        }
        return f;
    }
}
