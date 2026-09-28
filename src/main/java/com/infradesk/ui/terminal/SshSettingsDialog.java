package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.SshSettings;
import com.infradesk.ui.Async;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.WrappingLabel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

/** Registers the SSH user, port and private key for one server. */
public class SshSettingsDialog extends JDialog {

    private static final long MAX_KEY_BYTES = 32 * 1024;

    private final TerminalService service;
    private final Server server;
    private final JComboBox<String> username = new JComboBox<>(new String[] {"ubuntu", "opc", "ec2-user", "root"});
    private final JTextField port = new JTextField("22");
    private final JTextField keyPath = new JTextField();
    private final JPasswordField passphrase = new JPasswordField();
    private final WrappingLabel status = new WrappingLabel(" ", 436, -1, Theme.TEXT_SECONDARY);
    private final JButton saveButton = Buttons.primary("저장", null);
    private final boolean hadKey;

    private String keyPem;
    private boolean saved;
    private String suggestion;

    public SshSettingsDialog(Window owner, TerminalService service, Server server) {
        super(owner, "SSH 설정 · " + server.name(), ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.server = server;
        this.hadKey = service.hasKey(server.id());

        username.setEditable(true);
        service.settings(server.id()).ifPresentOrElse(s -> {
            username.setSelectedItem(s.username());
            port.setText(String.valueOf(s.port()));
        }, () -> service.suggestFromSshConfig(server).ifPresent(this::applySuggestion));
        styleField(username);
        username.putClientProperty(FlatClientProperties.STYLE,
                "background: #1E1F22; buttonStyle: button; buttonBackground: #1E1F22; buttonSeparatorWidth: 0;"
                        + " buttonArrowColor: #B4B8BF; buttonHoverArrowColor: #DFE1E5");
        styleField(port);
        styleField(keyPath);
        styleField(passphrase);
        keyPath.setEditable(false);
        port.enableInputMethods(false);
        keyPath.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                hadKey ? "등록된 키를 그대로 사용 (바꾸려면 찾아보기)" : "개인키 파일을 선택하세요 (예: ~/.ssh/id_ed25519)");
        passphrase.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "키에 암호가 없으면 비워 두세요");

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        if (suggestion != null) {
            showStatus(suggestion, false);
            status.setForeground(Theme.RUNNING_BADGE_TEXT);
        }
        getRootPane().setDefaultButton(saveButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setSize(480, getHeight());
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Pre-fills from ~/.ssh/config. The key is only read here; it's stored when the user saves. */
    private void applySuggestion(com.infradesk.ssh.SshConfig.Suggestion s) {
        if (s.user() != null) {
            username.setSelectedItem(s.user());
        }
        if (s.port() != null) {
            port.setText(String.valueOf(s.port()));
        }
        String source = "~/.ssh/config의 '" + s.alias() + "'에서 가져왔어요";
        if (s.identityFile() != null && !hadKey) {
            try {
                if (Files.size(s.identityFile()) <= MAX_KEY_BYTES) {
                    String content = Files.readString(s.identityFile(), StandardCharsets.UTF_8);
                    if (content.contains("PRIVATE KEY")) {
                        keyPem = content;
                        keyPath.setText(s.identityFile().getFileName().toString());
                        source += " (키: " + s.identityFile().getFileName() + ")";
                    }
                }
            } catch (IOException ignored) {
                // Leave the key for the user to pick.
            }
        }
        suggestion = source + ". 맞는지 확인하고 저장하세요.";
    }

    /** Shows the dialog; true when settings were saved. */
    public boolean showDialog() {
        setVisible(true);
        return saved;
    }

    private JPanel body() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

        JPanel row = new JPanel(new GridLayout(1, 2, 12, 0));
        row.setOpaque(false);
        row.add(labeled("사용자 이름", username));
        row.add(labeled("포트", port));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(row);
        body.add(Box.createVerticalStrut(14));

        JButton browse = Buttons.secondary("찾아보기", null);
        browse.addActionListener(e -> chooseKey());
        JPanel keyRow = new JPanel(new BorderLayout(8, 0));
        keyRow.setOpaque(false);
        keyRow.add(keyPath, BorderLayout.CENTER);
        keyRow.add(browse, BorderLayout.EAST);
        body.add(labeled("SSH 개인키", keyRow));
        body.add(Box.createVerticalStrut(14));
        body.add(labeled("키 암호 (선택)", passphrase));
        body.add(Box.createVerticalStrut(14));

        WrappingLabel hint = new WrappingLabel("Ubuntu 이미지는 보통 'ubuntu', Oracle Linux는 'opc'예요. "
                + "키는 암호화해서 이 PC에만 저장되고, 서버에 등록한 공개키와 짝이 맞아야 해요.", 436, -1, Theme.TEXT_MUTED);
        body.add(hint);
        body.add(Box.createVerticalStrut(8));
        body.add(status);
        return body;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setBackground(new java.awt.Color(0x26282B));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)));
        JButton cancel = Buttons.ghost("취소");
        cancel.addActionListener(e -> dispose());
        saveButton.addActionListener(e -> save());
        footer.add(cancel);
        footer.add(saveButton);
        return footer;
    }

    private void chooseKey() {
        JFileChooser chooser = new JFileChooser(new File(System.getProperty("user.home"), ".ssh"));
        chooser.setDialogTitle("SSH 개인키 선택");
        chooser.setFileHidingEnabled(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file.getName().endsWith(".pub")) {
            showStatus("'.pub'는 공개키예요. 같은 이름의 개인키 파일(확장자 없음)을 고르세요.", true);
            return;
        }
        try {
            if (Files.size(file.toPath()) > MAX_KEY_BYTES) {
                showStatus("파일이 너무 커요. SSH 개인키가 맞는지 확인하세요.", true);
                return;
            }
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (!content.contains("PRIVATE KEY")) {
                showStatus("SSH 개인키 형식이 아니에요.", true);
                return;
            }
            keyPem = content;
            keyPath.setText(file.getName());
            showStatus(" ", false);
        } catch (IOException e) {
            showStatus("파일을 읽지 못했어요: " + file.getName(), true);
        }
    }

    private void save() {
        String user = String.valueOf(username.getEditor().getItem()).strip();
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.getText().strip());
            if (portNumber <= 0 || portNumber > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            showStatus("포트는 1~65535 사이 숫자여야 해요.", true);
            return;
        }
        if (user.isEmpty()) {
            showStatus("사용자 이름을 입력하세요.", true);
            return;
        }
        if (keyPem == null && !hadKey) {
            showStatus("SSH 개인키 파일을 선택하세요.", true);
            return;
        }
        String pass = new String(passphrase.getPassword());
        SshSettings settings = new SshSettings(server.id(), user, portNumber);
        saveButton.setEnabled(false);
        showStatus("저장하는 중…", false);
        Async.run(() -> {
            service.save(settings, keyPem, keyPem != null || !pass.isEmpty() ? pass : null);
            return null;
        }, ignored -> {
            saved = true;
            keyPem = null;
            dispose();
        }, err -> {
            saveButton.setEnabled(true);
            showStatus(Async.message(err), true);
        });
    }

    private void showStatus(String message, boolean error) {
        status.setText(message);
        status.setForeground(error ? Theme.DANGER_TEXT : Theme.TEXT_SECONDARY);
    }

    private static void styleField(JComponent f) {
        f.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22");
        f.setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
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
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }
}
