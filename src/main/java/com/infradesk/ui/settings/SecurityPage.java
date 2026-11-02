package com.infradesk.ui.settings;

import com.infradesk.service.AppSettingsService;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.ToggleSwitch;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 설정 "보안·안전": 실수로 서버를 바꾸지 않도록, 그리고 비밀값이 새지 않도록.
 *
 * <ul>
 *   <li>공인 IP 기본으로 가리기: 동작한다. 처음 값은 켜짐이고, 끄면 앱을 열 때부터 보인다(눈 모양 버튼은 그대로 쓸 수 있다).
 *   <li>"되돌릴 수 없는 작업 확인"은 설정으로 두지 않는다. 서버 파일·폴더 삭제 같은 작업의 확인 창은 항상 뜬다.
 *   <li>관리 비밀번호: 읽기 모드에서 관리 모드로 바꿀 때 묻는 비밀번호를 바꾼다(현재 비밀번호를 확인한다).
 *   <li>"앱을 시작할 때 읽기 전용으로"는 설정으로 두지 않는다. 앱은 항상 읽기 모드로 시작한다.
 *   <li>활동 기록 보관, 알고 있는 호스트 키는 자리만 있다(준비 중).
 *   <li>비밀값 저장: 안내 글자.
 * </ul>
 */
public final class SecurityPage extends SettingsPage {

    public static final String ID = "security";

    private static final String HIDE_ON = "화면·로그에서 가리고, 눈 모양 버튼을 눌러야 보여요";
    private static final String HIDE_OFF = "앱을 열 때부터 보여요. 눈 모양 버튼으로 그 자리에서 가릴 수 있어요";

    private final ToggleSwitch hideIp = Controls.toggle(true);
    private final SettingsRow hideIpRow;

    /** @param onChangePassword [변경]을 눌렀을 때: 비밀번호 바꾸기 창을 연다 */
    public SecurityPage(AppSettingsService settings, Runnable onChangePassword) {
        super(ID, "보안·안전", "보안·안전", "실수로 서버를 바꾸지 않도록, 그리고 비밀값이 새지 않도록");
        hideIp.setSelected(settings.current().hidePublicIp());
        hideIpRow = new SettingsRow("공인 IP 기본으로 가리기", description(settings.current().hidePublicIp()), hideIp);
        hideIp.addActionListener(e -> {
            boolean on = hideIp.isSelected();
            try {
                settings.update(s -> s.withHidePublicIp(on));
                hideIpRow.setDescription(description(on));
            } catch (RuntimeException ex) {
                hideIp.setSelected(!on);   // 저장하지 못했다: 스위치를 되돌리고 이유를 보여 준다
                hideIpRow.setDescription(ex.getMessage());
            }
        });

        SettingsCard card = new SettingsCard();
        card.add(hideIpRow);
        JButton changePassword = Buttons.secondary("변경", null);
        changePassword.addActionListener(e -> onChangePassword.run());
        card.add(new SettingsRow("관리 비밀번호",
                "관리 모드로 바꿀 때 물어요 · 해시로만 저장돼요 · 바꾸려면 현재 비밀번호가 필요해요", changePassword));
        card.add(new SettingsRow("활동 기록 보관", "이 PC에만 저장 · 명령 출력과 비밀값은 남기지 않아요",
                Controls.choice(110, "30일")).comingSoon());
        card.add(new SettingsRow("비밀값 저장", "API 개인키·SSH 키·웹훅",
                Controls.value("secrets.vault · AES-256-GCM · 키는 OS 키체인")));
        card.add(knownHostKeys());
        addCard(card);
    }

    private static String description(boolean hide) {
        return hide ? HIDE_ON : HIDE_OFF;
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    ToggleSwitch hideIpSwitch() {
        return hideIp;
    }

    String hideIpDescription() {
        return hideIpRow.descriptionText();
    }

    /** "알고 있는 호스트 키": 처음 접속할 때 확인한 키 목록과 [잊기]. 준비 중이라 목록 자리에 안내만 둔다. */
    private static JPanel knownHostKeys() {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(16, 24, 18, 24));
        JPanel head = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        head.setOpaque(false);
        JLabel title = new JLabel("알고 있는 호스트 키");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        head.add(title);
        head.add(javax.swing.Box.createHorizontalStrut(8));
        head.add(ComingSoon.badge());
        JLabel desc = new JLabel("처음 접속할 때 확인한 키예요. 키가 바뀌면 연결을 막고 알려요.");
        desc.setForeground(Theme.TEXT_MUTED);
        JLabel body = new JLabel("호스트 키 목록과 [잊기]는 준비 중이에요.");
        body.setForeground(Theme.TEXT_MUTED);
        head.setAlignmentX(LEFT_ALIGNMENT);
        desc.setAlignmentX(LEFT_ALIGNMENT);
        body.setAlignmentX(LEFT_ALIGNMENT);
        box.add(head);
        box.add(javax.swing.Box.createVerticalStrut(4));
        box.add(desc);
        box.add(javax.swing.Box.createVerticalStrut(14));
        box.add(body);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(box);
        return wrap;
    }
}
