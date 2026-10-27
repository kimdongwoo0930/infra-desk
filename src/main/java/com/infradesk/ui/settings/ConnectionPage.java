package com.infradesk.ui.settings;

import com.infradesk.service.RefreshPolicy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.metrics.LiveController;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Duration;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 설정 "연결·조회": 얼마나 자주 묻고, SSH 연결을 어떻게 유지할지. 이 값들은 지금 코드에 고정되어 있어서 바꿀 수 있는
 * 설정이 아니라 현재 값을 글자로 보여 준다(주기를 바꾸면 PRD·README도 함께 고쳐야 하고 OCI 요청 제한과도 얽혀 있어
 * 따로 상의한다). "지금 열린 SSH 연결" 표는 연결 목록을 읽는 방법이 아직 없어서 틀만 만들어 두었다(준비 중).
 */
public final class ConnectionPage extends SettingsPage {

    public static final String ID = "connection";

    /** 메트릭 조회 주기. 모니터링 탭과 사이드바 CPU가 이 간격으로 읽는다(MainFrame의 메트릭 타이머와 같다). */
    static final Duration METRICS_INTERVAL = Duration.ofMinutes(1);
    /** 풀이 오래 열린 연결을 새로 맺는 간격(docs/architecture/ssh.md "수명 12시간"). */
    static final Duration CONNECTION_LIFETIME = Duration.ofHours(12);

    public ConnectionPage() {
        super(ID, "연결·조회", "연결·조회", "얼마나 자주 묻고, SSH 연결을 어떻게 유지할지");

        SettingsCard card = new SettingsCard();
        card.add(new SettingsRow("서버 상태 새로고침",
                "OCI API 요청 제한 때문에 " + Controls.duration(RefreshPolicy.NORMAL_INTERVAL)
                        + "마다 읽어요 · 시작·정지 직후에는 " + Controls.duration(RefreshPolicy.FAST_INTERVAL) + " 간격",
                Controls.value(Controls.duration(RefreshPolicy.NORMAL_INTERVAL))));
        card.add(new SettingsRow("메트릭", "OCI는 1분 단위로 모아요 · 직접 연결 서버는 SSH로 1분마다 읽어요",
                Controls.value(Controls.duration(METRICS_INTERVAL) + "마다")));
        card.add(new SettingsRow("실시간 모드 자동 꺼짐", "켜 둔 걸 잊어도 SSH로 계속 읽지 않게",
                Controls.value(Controls.duration(LiveController.AUTO_OFF) + " 뒤")));
        card.add(new SettingsRow("창이 메뉴 막대로 숨으면", "사이드바·메뉴 막대 CPU만 계속 보여줘요",
                Controls.value("조회 연결만 남기기")));
        card.add(new SettingsRow("SSH 연결 새로 맺기", "오래 열린 연결은 쓰지 않는 틈에 다시 맺어요 · 쓰지 않는 연결은 5~15분 뒤 닫아요",
                Controls.value(Controls.duration(CONNECTION_LIFETIME) + "마다")));
        addCard(card);
        addCard(openConnections());
    }

    /** "지금 열린 SSH 연결" 표: 서버 × 용도. 연결 목록을 읽는 방법이 아직 없어서 틀과 안내만 둔다(준비 중). */
    private static JPanel openConnections() {
        SettingsCard card = new SettingsCard();

        JPanel head = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(16, 24, 12, 24));
        JLabel title = new JLabel("지금 열린 SSH 연결");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        JLabel sub = new JLabel("서버마다 용도별로 하나씩");
        sub.setForeground(Theme.TEXT_MUTED);
        head.add(title);
        head.add(javax.swing.Box.createHorizontalStrut(10));
        head.add(sub);
        head.add(javax.swing.Box.createHorizontalStrut(10));
        head.add(ComingSoon.badge());
        card.add(head);

        JPanel columns = new JPanel(new GridLayout(1, 6));
        columns.setOpaque(false);
        columns.setBorder(BorderFactory.createEmptyBorder(10, 24, 10, 24));
        for (String name : new String[] {"서버", "조회", "명령", "파일", "전송", "상태"}) {
            JLabel l = new JLabel(name);
            l.setForeground(Theme.TEXT_MUTED);
            columns.add(l);
        }
        card.add(columns);

        JLabel empty = new JLabel("열린 연결을 보여 주는 기능은 준비 중이에요.");
        empty.setForeground(Theme.TEXT_MUTED);
        JPanel emptyBox = new JPanel(new BorderLayout());
        emptyBox.setOpaque(false);
        emptyBox.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        emptyBox.add(empty);
        card.add(emptyBox);

        JLabel note = new JLabel("터미널 탭은 이 표와 따로 연결돼요. 서버를 바꾸는 명령(명령 연결)은 실패해도 저절로 다시 보내지 않아요.");
        note.setForeground(Theme.TEXT_MUTED);
        JButton closeAll = Buttons.danger("모든 연결 끊기", null);
        JPanel foot = new JPanel(new BorderLayout(16, 0));
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createEmptyBorder(14, 24, 14, 24));
        note.setMinimumSize(new Dimension(0, note.getPreferredSize().height));
        foot.add(note, BorderLayout.CENTER);
        foot.add(ComingSoon.apply(closeAll), BorderLayout.EAST);
        card.add(foot);
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.add(card);
        return wrap;
    }
}
