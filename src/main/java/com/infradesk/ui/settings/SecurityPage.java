package com.infradesk.ui.settings;

import com.infradesk.ui.Theme;
import com.infradesk.ui.components.ComingSoon;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 설정 "보안·안전": 실수로 서버를 바꾸지 않도록, 그리고 비밀값이 새지 않도록. 지금은 "비밀값 저장" 안내만 실제
 * 정보이고, 나머지(공인 IP 기본 가리기, 읽기 전용, 되돌릴 수 없는 작업 확인, 활동 기록 보관, 알고 있는 호스트 키)는
 * 자리만 만들어 두었다(준비 중). 공인 IP 가리기는 지금도 기본으로 가려지지만(눈 버튼으로 보기) 기본값을 바꾸는
 * 설정은 없어서 켜진 상태로 비활성이다.
 */
public final class SecurityPage extends SettingsPage {

    public static final String ID = "security";

    public SecurityPage() {
        super(ID, "보안·안전", "보안·안전", "실수로 서버를 바꾸지 않도록, 그리고 비밀값이 새지 않도록");

        SettingsCard card = new SettingsCard();
        card.add(new SettingsRow("공인 IP 기본으로 가리기", "화면·로그에서 가리고, 눈 모양 버튼을 눌러야 보여요",
                Controls.toggle(true)).comingSoon());
        card.add(new SettingsRow("앱을 시작할 때 읽기 전용으로", "정지·재부팅·삭제·덮어쓰기를 막은 채로 시작해요",
                Controls.toggle(false)).comingSoon());
        card.add(new SettingsRow("되돌릴 수 없는 작업 확인", "서버 파일·폴더 삭제에 적용돼요",
                Controls.choice(150, "이름 입력까지")).comingSoon());
        card.add(new SettingsRow("활동 기록 보관", "이 PC에만 저장 · 명령 출력과 비밀값은 남기지 않아요",
                Controls.choice(110, "30일")).comingSoon());
        card.add(new SettingsRow("비밀값 저장", "API 개인키·SSH 키·웹훅",
                Controls.value("secrets.vault · AES-256-GCM · 키는 OS 키체인")));
        card.add(knownHostKeys());
        addCard(card);
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
