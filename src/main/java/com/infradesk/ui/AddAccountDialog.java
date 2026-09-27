package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.AccountSecrets;
import com.infradesk.core.ProviderType;
import com.infradesk.service.AccountValidation;
import com.infradesk.service.InventoryService;
import com.infradesk.ui.components.Buttons;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Modal dialog for registering a cloud account (docs/design/add-account.html). */
public class AddAccountDialog extends JDialog {

    private static final long MAX_KEY_BYTES = 16 * 1024;

    private final InventoryService service;
    private final boolean demoMode;

    private final JTextField displayName = field(false, "예: 계정 A");
    private final JTextField tenancy = field(true, "ocid1.tenancy.oc1..aaaa");
    private final JTextField user = field(true, "ocid1.user.oc1..aaaa");
    private final JTextField fingerprint = field(true, "12:34:56:78:9a:bc…");
    private final JComboBox<String> region;
    private final JTextField keyPath = field(false, "파일을 선택하세요");
    private final JLabel status = new JLabel(" ");
    private final JButton testButton = Buttons.secondary("연결 테스트", null);
    private final JButton saveButton = Buttons.primary("저장하고 서버 불러오기", null);

    private String privateKeyPem;
    private Account result;

    public AddAccountDialog(Window owner, InventoryService service, boolean demoMode) {
        super(owner, "계정 추가", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.demoMode = demoMode;
        this.region = new JComboBox<>(service.regions(ProviderType.ORACLE).toArray(String[]::new));
        region.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                String id = (String) value;
                String name = id == null ? "" : Regions.shortName(id);
                return super.getListCellRendererComponent(list, id == null || name.equals(id) ? id : id + " (" + name + ")",
                        index, sel, focus);
            }
        });
        region.setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
        region.putClientProperty(FlatClientProperties.STYLE,
                "background: #1E1F22; buttonStyle: button; buttonBackground: #1E1F22; buttonSeparatorWidth: 0; buttonArrowColor: #B4B8BF; buttonHoverArrowColor: #DFE1E5");
        region.getAccessibleContext().setAccessibleName("리전");

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND, Theme.PANEL_BG);
        getRootPane().setDefaultButton(saveButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        if (demoMode) {
            fillDemoValues();
        }
        pack();
        setSize(560, getHeight());
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog and returns the saved account, if any. */
    public Optional<Account> showDialog() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    private JPanel body() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));

        body.add(labeled("클라우드", cloudPicker()));
        body.add(Box.createVerticalStrut(16));
        body.add(labeled("표시 이름", displayName));
        body.add(Box.createVerticalStrut(16));
        body.add(labeled("Tenancy OCID", tenancy));
        body.add(Box.createVerticalStrut(16));
        body.add(labeled("User OCID", user));
        body.add(Box.createVerticalStrut(16));
        JPanel twoCols = new JPanel(new GridLayout(1, 2, 12, 0));
        twoCols.setOpaque(false);
        twoCols.add(labeled("Fingerprint", fingerprint));
        twoCols.add(labeled("리전", region));
        twoCols.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(twoCols);
        body.add(Box.createVerticalStrut(16));
        body.add(labeled("API 개인키 (.pem)", keyPicker()));
        body.add(Box.createVerticalStrut(16));
        body.add(notice());
        body.add(Box.createVerticalStrut(10));
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(status);
        return body;
    }

    private JPanel cloudPicker() {
        JPanel row = new JPanel(new GridLayout(1, 3, 8, 0));
        row.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (ProviderType type : ProviderType.values()) {
            JToggleButton b = new JToggleButton(type.isSupported() ? type.displayName() : type.displayName() + " · 준비 중");
            b.setPreferredSize(new Dimension(0, 44));
            b.setEnabled(type.isSupported());
            b.setSelected(type == ProviderType.ORACLE);
            b.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 16; background: #00000000; disabledBackground: #00000000; borderColor: #43454A; selectedBackground: #25324D;"
                            + " selectedForeground: #FFFFFF; font: +0 $medium.font;"
                            + " focusedBorderColor: #3B73E0; borderWidth: 1");
            if (type == ProviderType.ORACLE) {
                b.setBorder(BorderFactory.createCompoundBorder(
                        new com.formdev.flatlaf.ui.FlatLineBorder(new java.awt.Insets(0, 0, 0, 0), Theme.ACCENT, 2f, 16),
                        BorderFactory.createEmptyBorder()));
            }
            group.add(b);
            row.add(b);
        }
        return row;
    }

    private JPanel keyPicker() {
        keyPath.setEditable(false);
        JButton browse = Buttons.secondary("찾아보기", null);
        browse.addActionListener(e -> chooseKeyFile());
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(keyPath, BorderLayout.CENTER);
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    private JPanel notice() {
        JPanel box = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                var g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.APP_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.ARC_CARD * 2, Theme.ARC_CARD * 2);
                g2.dispose();
            }
        };
        box.setOpaque(false);
        box.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JLabel icon = new JLabel(Icons.get("lock", 16));
        icon.setVerticalAlignment(JLabel.TOP);
        String text = demoMode
                ? "데모 모드예요. 입력값과 키는 저장되지 않고, 가짜 서버가 표시돼요."
                : "API 개인키는 OS 키체인(macOS 키체인 / Windows 자격 증명 관리자)에 저장돼요. "
                        + "서버 SSH 키는 서버를 불러온 뒤 서버별로 등록해요.";
        JTextArea label = new JTextArea(text);
        label.setLineWrap(true);
        label.setWrapStyleWord(false);
        label.setEditable(false);
        label.setFocusable(false);
        label.setOpaque(false);
        label.setBorder(BorderFactory.createEmptyBorder());
        label.setForeground(Theme.TEXT_SECONDARY);
        label.setFont(UIManager.getFont("Label.font").deriveFont(UIManager.getFont("Label.font").getSize2D() - 1));
        // Give the wrapping text area its real width up front so its preferred height is right.
        label.setSize(new Dimension(560 - 44 - 24 - 16 - 10, Short.MAX_VALUE));
        box.add(icon, BorderLayout.WEST);
        box.add(label, BorderLayout.CENTER);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        return box;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new java.awt.Color(0x26282B));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)));
        JButton cancel = Buttons.ghost("취소");
        cancel.addActionListener(e -> dispose());
        testButton.addActionListener(e -> testConnection());
        saveButton.addActionListener(e -> save());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(cancel);
        right.add(saveButton);
        footer.add(testButton, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private void chooseKeyFile() {
        JFileChooser chooser = new JFileChooser(new File(System.getProperty("user.home"), ".oci"));
        chooser.setDialogTitle("API 개인키 선택");
        chooser.setFileFilter(new FileNameExtensionFilter("PEM 키 (*.pem)", "pem"));
        chooser.setFileHidingEnabled(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            if (Files.size(file.toPath()) > MAX_KEY_BYTES) {
                showStatus("키 파일이 너무 커요. API 개인키(.pem)가 맞는지 확인하세요.", true);
                return;
            }
            privateKeyPem = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            keyPath.setText(file.getName());
            showStatus(" ", false);
        } catch (IOException e) {
            showStatus("키 파일을 읽지 못했어요: " + file.getName(), true);
        }
    }

    private Optional<Account> buildAccount() {
        String keyForValidation = privateKeyPem == null && !demoMode ? "" : privateKeyPem;
        List<String> errors = AccountValidation.oracle(displayName.getText(), tenancy.getText(), user.getText(),
                fingerprint.getText(), (String) region.getSelectedItem(), keyForValidation);
        if (!errors.isEmpty()) {
            showStatus(errors.getFirst(), true);
            return Optional.empty();
        }
        Map<String, String> props = new LinkedHashMap<>();
        props.put("tenancyOcid", tenancy.getText().strip());
        props.put("userOcid", user.getText().strip());
        props.put("fingerprint", fingerprint.getText().strip().toLowerCase(java.util.Locale.ROOT));
        return Optional.of(new Account(UUID.randomUUID().toString(), displayName.getText().strip(),
                ProviderType.ORACLE, (String) region.getSelectedItem(), props));
    }

    private Map<String, String> secrets() {
        Map<String, String> s = new HashMap<>();
        if (privateKeyPem != null) {
            s.put(AccountSecrets.PRIVATE_KEY, privateKeyPem);
        }
        return s;
    }

    private void testConnection() {
        Optional<Account> account = buildAccount();
        if (account.isEmpty()) {
            return;
        }
        setBusy(true, "연결하는 중…");
        Async.run(() -> service.testConnection(account.get(), secrets()),
                count -> {
                    setBusy(false, null);
                    showStatus("연결 성공 · 서버 " + count + "대를 찾았어요", false);
                    status.setForeground(Theme.RUNNING_BADGE_TEXT);
                },
                err -> {
                    setBusy(false, null);
                    showStatus(Async.message(err), true);
                });
    }

    private void save() {
        Optional<Account> account = buildAccount();
        if (account.isEmpty()) {
            return;
        }
        setBusy(true, "저장하는 중…");
        Async.run(() -> {
                    service.addAccount(account.get(), secrets());
                    return account.get();
                },
                saved -> {
                    result = saved;
                    privateKeyPem = null;
                    dispose();
                },
                err -> {
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

    private void fillDemoValues() {
        int n = service.accounts().size() + 1;
        displayName.setText("데모 계정 " + n);
        tenancy.setText("ocid1.tenancy.oc1..demo" + n);
        user.setText("ocid1.user.oc1..demo" + n);
        fingerprint.setText("00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff");
        keyPath.setText("(데모 모드: 키 파일 필요 없음)");
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

    private static JTextField field(boolean mono, String placeholder) {
        JTextField f = new JTextField();
        f.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        f.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22; margin: 0,4,0,4");
        f.setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
        if (mono) {
            f.setFont(Theme.monoFont(12f));
        }
        return f;
    }
}
