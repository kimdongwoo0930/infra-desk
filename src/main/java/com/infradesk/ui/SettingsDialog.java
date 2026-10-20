package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.alert.AlertService;
import com.infradesk.alert.AlertSettings;
import com.infradesk.alert.DiscordNotifier;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.WrappingLabel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;

/** 앱 설정: 디스코드 웹훅 알림. */
public class SettingsDialog extends JDialog {

    private final AlertService alerts;
    private final boolean demoMode;
    private final com.infradesk.service.UpdateService updates;
    private final com.infradesk.app.LaunchAtLogin launchAtLogin;
    private final JCheckBox startAtLogin = new JCheckBox("로그인할 때 InfraDesk 실행 (메뉴 막대에서 시작)");
    private final JCheckBox autoUpdate = new JCheckBox("새 베타 빌드 자동 확인 (시작할 때, 6시간마다)");
    private final WrappingLabel appStatus = new WrappingLabel(" ", 476, -1, Theme.TEXT_SECONDARY);
    private final Runnable checkNow;
    private final JCheckBox enabled = new JCheckBox("디스코드 알림 사용");
    private final JPasswordField webhook = new JPasswordField();
    private final JCheckBox serverDown = new JCheckBox("서버가 예상치 않게 멈추거나 다시 켜졌을 때");
    private final JCheckBox accountError = new JCheckBox("계정에 연결하지 못하거나 복구됐을 때");
    private final JCheckBox cpuHigh = new JCheckBox("CPU 사용률이 높게 유지될 때");
    private final JSpinner cpuThreshold = new JSpinner(new SpinnerNumberModel(90, 10, 100, 5));
    private final JSpinner cpuMinutes = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final WrappingLabel status = new WrappingLabel(" ", 476, -1, Theme.TEXT_SECONDARY);
    private final JButton testButton = Buttons.secondary("테스트 메시지 보내기", null);
    private final JButton saveButton = Buttons.primary("저장", null);

    public SettingsDialog(Window owner, AlertService alerts, boolean demoMode) {
        this(owner, alerts, demoMode, null, null, null);
    }

    /**
     * @param updates       업데이트 확인. null이면 그 섹션을 숨긴다
     * @param launchAtLogin 로그인 시 실행 항목 제어. null이면 숨긴다
     * @param checkNow      업데이트 확인을 실행하고 {@link #showUpdateResult}로 결과를 알린다
     */
    public SettingsDialog(Window owner, AlertService alerts, boolean demoMode,
                          com.infradesk.service.UpdateService updates, com.infradesk.app.LaunchAtLogin launchAtLogin,
                          Runnable checkNow) {
        super(owner, "설정", ModalityType.APPLICATION_MODAL);
        this.alerts = alerts;
        this.demoMode = demoMode;
        this.updates = updates;
        this.launchAtLogin = launchAtLogin;
        this.checkNow = checkNow;

        AlertSettings s = alerts.settings();
        enabled.setSelected(s.enabled());
        serverDown.setSelected(s.serverDown());
        accountError.setSelected(s.accountError());
        cpuHigh.setSelected(s.cpuHigh());
        cpuThreshold.setValue(s.cpuThreshold());
        cpuMinutes.setValue(s.cpuMinutes());
        webhook.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, alerts.hasWebhook()
                ? "저장된 웹훅 사용 중 (바꾸려면 새 URL 입력)" : "https://discord.com/api/webhooks/…");
        webhook.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22; showRevealButton: true");
        webhook.setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
        webhook.getAccessibleContext().setAccessibleName("디스코드 웹훅 URL");
        webhook.enableInputMethods(false);
        enabled.addActionListener(e -> updateEnabled());
        cpuHigh.addActionListener(e -> updateEnabled());
        testButton.addActionListener(e -> sendTest());
        saveButton.addActionListener(e -> save());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(saveButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        updateEnabled();
        pack();
        setSize(520, getHeight());
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private JPanel body() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));

        if (updates != null || launchAtLogin != null) {
            addGeneralSections(body);
        }

        JLabel title = new JLabel(AlertService.AVAILABLE ? "디스코드 알림" : "디스코드 알림 · 준비 중");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        add(body, title);
        body.add(Box.createVerticalStrut(4));
        if (!AlertService.AVAILABLE) {
            add(body, new WrappingLabel("서버가 멈추거나 CPU가 계속 높을 때 디스코드로 알려 주는 기능이에요. "
                    + "다음 업데이트에서 열릴 예정이에요.", 476, -1, Theme.TEXT_MUTED));
            return body;
        }
        add(body, muted(demoMode
                ? "데모 모드예요. 실제로 보내지 않고 화면 구석에 미리보기만 띄워요."
                : "앱이 실행 중일 때만 알림을 보내요. 웹훅 URL은 암호화해서 이 PC에만 저장돼요."));
        body.add(Box.createVerticalStrut(14));
        add(body, enabled);
        body.add(Box.createVerticalStrut(10));
        JLabel urlLabel = new JLabel("웹훅 URL");
        urlLabel.setForeground(Theme.TEXT_SECONDARY);
        urlLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        urlLabel.setLabelFor(webhook);
        add(body, urlLabel);
        body.add(Box.createVerticalStrut(6));
        webhook.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.BUTTON_HEIGHT));
        add(body, webhook);
        body.add(Box.createVerticalStrut(4));
        add(body, muted("디스코드 채널 설정 → 연동 → 웹후크에서 만들 수 있어요."));
        body.add(Box.createVerticalStrut(14));
        add(body, serverDown);
        add(body, accountError);
        add(body, cpuHigh);
        JPanel cpuRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cpuRow.setOpaque(false);
        cpuRow.setBorder(BorderFactory.createEmptyBorder(4, 26, 0, 0));
        cpuRow.add(cpuThreshold);
        cpuRow.add(new JLabel("% 이상이"));
        cpuRow.add(cpuMinutes);
        cpuRow.add(new JLabel("분 넘게 계속되면"));
        cpuThreshold.getAccessibleContext().setAccessibleName("CPU 기준 퍼센트");
        cpuMinutes.getAccessibleContext().setAccessibleName("지속 시간(분)");
        add(body, cpuRow);
        body.add(Box.createVerticalStrut(12));
        add(body, status);
        return body;
    }

    /** "일반"(로그인 시 실행, 자동 업데이트)과 "앱 정보"(버전, 업데이트 확인, 로그). */
    private void addGeneralSections(JPanel body) {
        JLabel general = new JLabel("일반");
        general.setFont(general.getFont().deriveFont(Font.BOLD, 15f));
        add(body, general);
        body.add(Box.createVerticalStrut(10));
        if (launchAtLogin != null) {
            startAtLogin.setSelected(launchAtLogin.isEnabled());
            startAtLogin.setEnabled(launchAtLogin.isSupported() && !demoMode);
            add(body, startAtLogin);
            String where = demoMode ? "데모 모드에서는 바꿀 수 없어요."
                    : launchAtLogin.target().map(p -> "실행 대상: " + p).orElse("설치한 InfraDesk를 찾지 못했어요 (응용 프로그램 폴더에 설치하면 켤 수 있어요).");
            WrappingLabel hint = new WrappingLabel(where, 452, -2, Theme.TEXT_MUTED);
            hint.setBorder(BorderFactory.createEmptyBorder(0, 26, 6, 0));
            add(body, hint);
        }
        if (updates != null) {
            autoUpdate.setSelected(updates.autoCheck());
            autoUpdate.setEnabled(!demoMode);
            add(body, autoUpdate);
        }
        body.add(Box.createVerticalStrut(18));

        JLabel about = new JLabel("앱 정보");
        about.setFont(about.getFont().deriveFont(Font.BOLD, 15f));
        add(body, about);
        body.add(Box.createVerticalStrut(6));
        JLabel version = new JLabel("InfraDesk " + com.infradesk.app.BuildInfo.current().display());
        version.setForeground(Theme.TEXT_SECONDARY);
        add(body, version);
        body.add(Box.createVerticalStrut(8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        JButton check = Buttons.secondary("업데이트 확인", "refresh");
        check.setEnabled(checkNow != null && !demoMode);
        check.addActionListener(e -> {
            appStatus.setText("확인하는 중…");
            appStatus.setForeground(Theme.TEXT_SECONDARY);
            checkNow.run();
        });
        JButton logs = Buttons.secondary("로그 폴더 열기", "folder");
        logs.addActionListener(e -> ErrorReporter.openLogFolder(com.infradesk.storage.AppPaths.logDir()));
        JButton licenses = Buttons.secondary("오픈소스 라이선스", null);
        licenses.addActionListener(e -> new LicensesDialog(this).setVisible(true));
        buttons.add(check);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(logs);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(licenses);
        add(body, buttons);
        body.add(Box.createVerticalStrut(6));
        add(body, appStatus);
        body.add(Box.createVerticalStrut(18));
        JPanel divider = new JPanel();
        divider.setBackground(Theme.DIVIDER);
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        divider.setPreferredSize(new Dimension(0, 1));
        add(body, divider);
        body.add(Box.createVerticalStrut(18));
    }

    /** 이 다이얼로그에서 시작한 업데이트 확인의 결과와 함께 EDT에서 호출된다. */
    public void showUpdateResult(String message, boolean newer) {
        appStatus.setText(message);
        appStatus.setForeground(newer ? Theme.RUNNING_BADGE_TEXT : Theme.TEXT_SECONDARY);
        pack();
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new java.awt.Color(0x26282B));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)));
        JButton cancel = Buttons.ghost("취소");
        cancel.addActionListener(e -> dispose());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(cancel);
        right.add(saveButton);
        if (AlertService.AVAILABLE) {
            footer.add(testButton, BorderLayout.WEST);
        }
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private void updateEnabled() {
        boolean on = enabled.isSelected();
        for (JComponent c : new JComponent[] {webhook, serverDown, accountError, cpuHigh}) {
            c.setEnabled(on);
        }
        cpuThreshold.setEnabled(on && cpuHigh.isSelected());
        cpuMinutes.setEnabled(on && cpuHigh.isSelected());
    }

    /** 입력란에 새로 입력한 URL. 저장된 값을 유지하려면 null. */
    private String typedUrl() {
        String typed = new String(webhook.getPassword()).strip();
        return typed.isEmpty() ? null : typed;
    }

    private boolean validUrl() {
        String typed = typedUrl();
        if (typed != null && !DiscordNotifier.isWebhookUrl(typed)) {
            showStatus("디스코드 웹훅 URL 형식이 아니에요 (https://discord.com/api/webhooks/…)", true);
            return false;
        }
        if (enabled.isSelected() && typed == null && !alerts.hasWebhook() && !demoMode) {
            showStatus("웹훅 URL을 입력하세요.", true);
            return false;
        }
        return true;
    }

    private AlertSettings current() {
        return new AlertSettings(enabled.isSelected(), serverDown.isSelected(), accountError.isSelected(),
                cpuHigh.isSelected(), (Integer) cpuThreshold.getValue(), (Integer) cpuMinutes.getValue());
    }

    private void sendTest() {
        if (!validUrl()) {
            return;
        }
        String typed = typedUrl();
        testButton.setEnabled(false);
        showStatus("보내는 중…", false);
        Async.run(() -> {
            if (typed != null) {
                alerts.save(alerts.settings(), typed);
            }
            alerts.sendTest();
            return null;
        }, ignored -> {
            testButton.setEnabled(true);
            showStatus(demoMode ? "미리보기를 띄웠어요 (데모)" : "보냈어요. 디스코드 채널을 확인하세요.", false);
            status.setForeground(Theme.RUNNING_BADGE_TEXT);
        }, err -> {
            testButton.setEnabled(true);
            showStatus(Async.message(err), true);
        });
    }

    private void save() {
        if (AlertService.AVAILABLE && !validUrl()) {
            return;
        }
        AlertSettings s = current();
        String typed = typedUrl();
        boolean login = startAtLogin.isSelected();
        boolean auto = autoUpdate.isSelected();
        saveButton.setEnabled(false);
        Async.run(() -> {
            if (AlertService.AVAILABLE) {
                alerts.save(s, typed);
            }
            if (launchAtLogin != null && launchAtLogin.isSupported() && !demoMode && login != launchAtLogin.isEnabled()) {
                launchAtLogin.setEnabled(login);
            }
            if (updates != null && !demoMode) {
                updates.setAutoCheck(auto);
            }
            return null;
        }, ignored -> dispose(), err -> {
            saveButton.setEnabled(true);
            showStatus(Async.message(err), true);
        });
    }

    private void showStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? Theme.DANGER_TEXT : Theme.TEXT_SECONDARY);
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT_MUTED);
        l.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        return l;
    }

    private static void add(JPanel body, JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (c instanceof JCheckBox box) {
            box.setOpaque(false);
        }
        body.add(c);
    }
}
