package com.infradesk.ui.settings;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.alert.AlertService;
import com.infradesk.alert.AlertSettings;
import com.infradesk.alert.DiscordNotifier;
import com.infradesk.ui.Async;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.components.ToggleSwitch;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * 설정 "알림": 디스코드로 알려 주는 기능. 바꾼 값은 바로 저장된다(스위치·체크·숫자는 바꾸는 즉시, 웹훅 URL은 Enter를
 * 누르거나 칸을 떠날 때 형식을 확인해서). 서버 멈춤, 계정 오류, CPU 기준 알림은 기존 동작을 옮긴 것이고, 앱 안 알림,
 * 다시 보내기 간격, 디스크·컨테이너 알림 규칙은 아직 없어서 자리만 만들어 두었다(준비 중).
 *
 * <p>알림 기능 전체가 아직 열리지 않은 빌드({@link AlertService#AVAILABLE}가 false)에서는 모든 컨트롤이 비활성이다.
 */
public final class AlertsPage extends SettingsPage {

    public static final String ID = "alerts";

    private final AlertService alerts;
    private final boolean demoMode;
    private final Runnable onChanged;

    private final ToggleSwitch enabled = Controls.toggle(false);
    private final JPasswordField webhook = new JPasswordField();
    private final JCheckBox serverDown = new JCheckBox("서버가 예상치 않게 멈추거나 다시 켜졌을 때");
    private final JCheckBox accountError = new JCheckBox("계정에 연결하지 못하거나 복구됐을 때");
    private final JCheckBox cpuHigh = new JCheckBox("CPU가");
    private final JSpinner cpuThreshold = new JSpinner(new SpinnerNumberModel(90, 10, 100, 5));
    private final JSpinner cpuMinutes = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JButton testButton = Buttons.secondary("테스트 메시지 보내기", null);
    private final JLabel status = new JLabel(" ");
    private boolean saving;
    private boolean dirty;

    /**
     * @param onChanged 알림 설정이 저장될 때(EDT) 불린다. 모니터링 탭의 알림 기준선을 새로 그리는 데 쓴다
     */
    public AlertsPage(AlertService alerts, boolean demoMode, Runnable onChanged) {
        super(ID, "알림", "알림", "앱이 켜져 있는 동안 디스코드와 화면 구석으로 알려요");
        this.alerts = alerts;
        this.demoMode = demoMode;
        this.onChanged = onChanged;

        AlertSettings s = alerts.settings();
        enabled.setSelected(s.enabled());
        serverDown.setSelected(s.serverDown());
        accountError.setSelected(s.accountError());
        cpuHigh.setSelected(s.cpuHigh());
        cpuThreshold.setValue(s.cpuThreshold());
        cpuMinutes.setValue(s.cpuMinutes());
        webhook.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder(alerts.hasWebhook()));
        webhook.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22; showRevealButton: true");
        webhook.setFont(Theme.monoFont(12.5f));
        webhook.setPreferredSize(new Dimension(380, Theme.BUTTON_HEIGHT));
        webhook.getAccessibleContext().setAccessibleName("디스코드 웹훅 URL");
        webhook.enableInputMethods(false);
        cpuThreshold.getAccessibleContext().setAccessibleName("CPU 기준 퍼센트");
        cpuMinutes.getAccessibleContext().setAccessibleName("지속 시간(분)");
        for (JCheckBox box : new JCheckBox[] {serverDown, accountError, cpuHigh}) {
            box.setOpaque(false);
        }

        wire();

        if (!AlertService.AVAILABLE) {
            addCard(notAvailableNote());
        }
        SettingsCard card = new SettingsCard();
        SettingsRow switchRow = new SettingsRow("디스코드 알림",
                demoMode ? "데모 모드예요. 실제로 보내지 않고 화면 구석에 미리보기만 띄워요." : "앱이 켜져 있는 동안만 보내요", enabled);
        SettingsRow urlRow = new SettingsRow("웹훅 URL", "디스코드 채널 설정 → 연동 → 웹후크 · 암호화해서 이 PC에만 저장돼요", webhook);
        SettingsRow testRow = new SettingsRow("테스트 메시지", "지금 설정으로 한 번 보내 봐요", testButton);
        if (!AlertService.AVAILABLE) {
            switchRow.comingSoon();
            urlRow.comingSoon();
            testRow.comingSoon();
        }
        card.add(switchRow);
        card.add(urlRow);
        card.add(testRow);
        card.add(new SettingsRow("앱 안 알림", "오른쪽 아래에 잠깐 띄워요", Controls.toggle(true)).comingSoon());
        card.add(new SettingsRow("같은 알림 다시 보내기", "문제가 계속될 때 반복 알림 간격", Controls.choice(130, "30분마다")).comingSoon());
        card.add(rules());
        addCard(card);

        status.setForeground(Theme.TEXT_MUTED);
        status.setBorder(BorderFactory.createEmptyBorder(12, 4, 0, 0));
        addCard(status);
        updateEnabled();
        if (!AlertService.AVAILABLE) {
            for (JComponent c : new JComponent[] {serverDown, accountError, cpuHigh, cpuThreshold, cpuMinutes}) {
                c.setEnabled(false);
            }
        }
    }

    // ── 화면 ────────────────────────────────────────────────────

    private JComponent notAvailableNote() {
        RoundedPanel note = new RoundedPanel(new BorderLayout(), Theme.INFO_BG, Theme.INFO_BORDER, Theme.ARC_CARD);
        note.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));
        JLabel text = new JLabel("서버가 멈추거나 CPU가 계속 높을 때 디스코드로 알려 주는 기능이에요. 다음 업데이트에서 열릴 예정이에요.");
        text.setForeground(Theme.TEXT);
        note.add(text);
        return note;
    }

    /** "이럴 때 알려 주세요": 알림 종류 체크 목록. */
    private JPanel rules() {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(16, 24, 18, 24));
        JLabel heading = new JLabel("이럴 때 알려 주세요");
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        heading.setAlignmentX(LEFT_ALIGNMENT);
        box.add(heading);
        box.add(Box.createVerticalStrut(10));
        box.add(line(serverDown));
        box.add(line(accountError));
        box.add(line(cpuHigh, cpuThreshold, new JLabel("% 이상이"), cpuMinutes, new JLabel("분 넘게 계속될 때")));

        // 준비 중: 알림 규칙 두 가지(디스크, 컨테이너 멈춤). AlertMonitor가 아직 CPU·서버·계정만 본다.
        JCheckBox disk = new JCheckBox("디스크(/) 사용량이", true);
        JSpinner diskThreshold = new JSpinner(new SpinnerNumberModel(90, 10, 100, 5));
        JCheckBox container = new JCheckBox("컨테이너가 예상치 않게 멈췄을 때", false);
        for (JCheckBox c : new JCheckBox[] {disk, container}) {
            c.setOpaque(false);
        }
        box.add(ComingSoon.apply(line(disk, diskThreshold, new JLabel("%를 넘을 때"), ComingSoon.badge())));
        box.add(ComingSoon.apply(line(container, ComingSoon.badge())));
        return box;
    }

    private static JPanel line(JComponent... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        for (JComponent part : parts) {
            p.add(part);
        }
        p.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    // ── 동작 ────────────────────────────────────────────────────

    private void wire() {
        enabled.addActionListener(e -> {
            if (enabled.isSelected() && !demoMode && !alerts.hasWebhook() && typedUrl() == null) {
                enabled.setSelected(false);
                showStatus("먼저 웹훅 URL을 입력해 주세요.", true);
                return;
            }
            updateEnabled();
            queueSave();
        });
        for (JCheckBox box : new JCheckBox[] {serverDown, accountError, cpuHigh}) {
            box.addActionListener(e -> {
                updateEnabled();
                queueSave();
            });
        }
        cpuThreshold.addChangeListener(e -> queueSave());
        cpuMinutes.addChangeListener(e -> queueSave());
        webhook.addActionListener(e -> commitWebhook());
        webhook.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                commitWebhook();
            }
        });
        testButton.addActionListener(e -> sendTest());
    }

    /** 스위치가 꺼져 있으면 웹훅과 알림 종류를 비활성으로 보여 준다(알림 기능이 열려 있을 때만). */
    private void updateEnabled() {
        if (!AlertService.AVAILABLE) {
            return;
        }
        boolean on = enabled.isSelected();
        for (JComponent c : new JComponent[] {webhook, serverDown, accountError, cpuHigh}) {
            c.setEnabled(on);
        }
        cpuThreshold.setEnabled(on && cpuHigh.isSelected());
        cpuMinutes.setEnabled(on && cpuHigh.isSelected());
    }

    private AlertSettings current() {
        return new AlertSettings(enabled.isSelected(), serverDown.isSelected(), accountError.isSelected(),
                cpuHigh.isSelected(), (Integer) cpuThreshold.getValue(), (Integer) cpuMinutes.getValue());
    }

    /** 입력란에 새로 입력한 URL. 저장된 값을 유지하려면 null. */
    private String typedUrl() {
        String typed = new String(webhook.getPassword()).strip();
        return typed.isEmpty() ? null : typed;
    }

    /**
     * 설정을 저장한다. 연달아 바꾸면(숫자 칸의 화살표 등) 저장은 한 번에 하나씩만 하고, 저장하는 동안 또 바뀌었으면
     * 끝난 뒤 최신 값으로 한 번 더 저장한다(저장이 뒤섞여 옛 값이 남지 않게).
     */
    private void queueSave() {
        if (!AlertService.AVAILABLE) {
            return;
        }
        if (saving) {
            dirty = true;
            return;
        }
        saving = true;
        AlertSettings s = current();
        Async.run(() -> {
            alerts.save(s, null);
            return null;
        }, ignored -> {
            saving = false;
            onChanged.run();
            if (dirty) {
                dirty = false;
                queueSave();
            } else {
                showStatus("저장했어요", false);
            }
        }, err -> {
            saving = false;
            dirty = false;
            showStatus(Async.message(err), true);
        });
    }

    /** 새로 입력한 웹훅 URL을 확인해서 저장한다(Enter, 또는 칸을 떠날 때). 입력이 없으면 아무것도 하지 않는다. */
    private void commitWebhook() {
        String typed = typedUrl();
        if (typed == null || !AlertService.AVAILABLE) {
            return;
        }
        String problem = webhookProblem(typed, false, alerts.hasWebhook(), demoMode);
        if (problem != null) {
            showStatus(problem, true);
            return;
        }
        Async.run(() -> {
            alerts.save(alerts.settings(), typed);
            return null;
        }, ignored -> {
            webhook.setText("");
            webhook.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder(true));
            showStatus("웹훅을 저장했어요", false);
            onChanged.run();
        }, err -> showStatus(Async.message(err), true));
    }

    private void sendTest() {
        String typed = typedUrl();
        String problem = webhookProblem(typed, true, alerts.hasWebhook(), demoMode);
        if (problem != null) {
            showStatus(problem, true);
            return;
        }
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
            webhook.setText("");
            webhook.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder(alerts.hasWebhook() || typed != null));
            showStatus(demoMode ? "미리보기를 띄웠어요 (데모)" : "보냈어요. 디스코드 채널을 확인하세요.", false);
            status.setForeground(Theme.RUNNING_BADGE_TEXT);
        }, err -> {
            testButton.setEnabled(true);
            showStatus(Async.message(err), true);
        });
    }

    private void showStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? Theme.DANGER_TEXT : Theme.TEXT_MUTED);
    }

    // ── 문구 (순수 함수, 테스트용으로 열어 둔다) ────────────────

    static String placeholder(boolean hasWebhook) {
        return hasWebhook ? "저장된 웹훅 사용 중 (바꾸려면 새 URL 입력)" : "https://discord.com/api/webhooks/…";
    }

    /**
     * 웹훅 URL을 확인해서 문제가 있으면 한국어 안내를, 괜찮으면 null을 돌려준다.
     *
     * @param typed      새로 입력한 URL(없으면 null)
     * @param needsOne   웹훅이 꼭 있어야 하는 경우(알림을 켰거나 테스트를 보낼 때)
     * @param hasWebhook 이미 저장된 웹훅이 있는지
     * @param demoMode   데모 모드(실제로 보내지 않으므로 웹훅이 없어도 된다)
     */
    static String webhookProblem(String typed, boolean needsOne, boolean hasWebhook, boolean demoMode) {
        if (typed != null && !DiscordNotifier.isWebhookUrl(typed)) {
            return "디스코드 웹훅 URL 형식이 아니에요 (https://discord.com/api/webhooks/…)";
        }
        if (needsOne && typed == null && !hasWebhook && !demoMode) {
            return "웹훅 URL을 입력하세요.";
        }
        return null;
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    ToggleSwitch enabledSwitch() {
        return enabled;
    }

    JPasswordField webhookField() {
        return webhook;
    }

    JCheckBox cpuHighBox() {
        return cpuHigh;
    }

    JButton testButton() {
        return testButton;
    }

    String statusText() {
        return status.getText();
    }
}
