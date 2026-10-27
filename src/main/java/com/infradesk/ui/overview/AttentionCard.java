package com.infradesk.ui.overview;

import com.infradesk.ssh.Container;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.containers.ContainerFormat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 개요 오른쪽 열 맨 위의 "확인 필요" 카드 (디자인 v2): 멈춘 컨테이너가 있으면 "watchtower 컨테이너가 멈춰 있어요 · 3일 전
 * 종료 · 종료 코드 0"와 [로그 보기] [시작]을 보여 준다. 멈춘 것이 없으면 카드가 보이지 않는다.
 *
 * <p>컨테이너 탭이 읽은 목록에서 그때그때 판단한다(새 조회 없음). 시안의 "이 알림 숨기기"는 숨김 상태를 저장할 곳이
 * 없어서 두지 않았다.
 */
public final class AttentionCard extends RoundedPanel {

    private static final Pattern STOPPED = Pattern.compile("^멈춤 \\((-?\\d+)\\) · (.+) 전$");

    private final JLabel title = new JLabel(" ");
    private final JLabel detail = new JLabel(" ");
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private Consumer<Container> onLogs = c -> { };
    private Consumer<Container> onStart = c -> { };
    private Runnable onViewContainers = () -> { };

    public AttentionCard() {
        super(new BorderLayout(12, 0), new Color(0x2B2618), Theme.WARNING_BORDER, Theme.ARC_CARD);
        setAlignmentX(LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel mark = new JLabel("⚠");
        mark.setForeground(Theme.WARNING);
        mark.setFont(mark.getFont().deriveFont(18f));
        mark.setVerticalAlignment(javax.swing.SwingConstants.TOP);

        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        detail.setForeground(Theme.TEXT_MUTED);
        // 이름이 길어도 카드를 넓히지 않고 "…"로 끝나게 한다.
        title.setMinimumSize(new Dimension(0, title.getPreferredSize().height));
        detail.setMinimumSize(new Dimension(0, detail.getPreferredSize().height));
        buttons.setOpaque(false);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        for (java.awt.Component c : new java.awt.Component[] {title, detail, buttons}) {
            ((javax.swing.JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
        }
        text.add(title);
        text.add(javax.swing.Box.createVerticalStrut(4));
        text.add(detail);
        text.add(javax.swing.Box.createVerticalStrut(12));
        text.add(buttons);
        add(mark, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
        setVisible(false);
    }

    public void onLogs(Consumer<Container> listener) {
        this.onLogs = listener;
    }

    public void onStart(Consumer<Container> listener) {
        this.onStart = listener;
    }

    /** 멈춘 컨테이너가 여러 개일 때의 [컨테이너 보기]. */
    public void onViewContainers(Runnable listener) {
        this.onViewContainers = listener;
    }

    /** 컨테이너 목록으로 카드를 갱신한다. 멈춘 것이 없으면 카드를 숨긴다. */
    public void setContainers(List<Container> containers) {
        List<Container> stopped = containers.stream().filter(AttentionCard::isStopped).toList();
        setVisible(!stopped.isEmpty());
        buttons.removeAll();
        if (stopped.isEmpty()) {
            return;
        }
        title.setText(titleText(stopped));
        detail.setText(detailText(stopped));
        detail.setToolTipText(detail.getText());
        if (stopped.size() == 1) {
            Container only = stopped.getFirst();
            JButton logs = Buttons.secondary("로그 보기", null);
            logs.addActionListener(e -> onLogs.accept(only));
            JButton start = Buttons.secondary("시작", null);
            start.addActionListener(e -> onStart.accept(only));
            buttons.add(logs);
            buttons.add(start);
        } else {
            JButton view = Buttons.secondary("컨테이너 보기", null);
            view.addActionListener(e -> onViewContainers.run());
            buttons.add(view);
        }
        revalidate();
        repaint();
    }

    /** 컨테이너를 보여 줄 수 없을 때(서버 꺼짐, SSH 미설정 등)는 판단할 것이 없으니 카드를 숨긴다. */
    public void clear() {
        setVisible(false);
        revalidate();
    }

    // ── 글자 (순수 함수, 테스트용으로 열어 둔다) ────────────────

    /** 멈춘 컨테이너인지: 종료했거나(exited) 죽었다(dead). 재시작 중·일시정지·생성됨은 해당하지 않는다. */
    static boolean isStopped(Container c) {
        return "exited".equals(c.state()) || "dead".equals(c.state());
    }

    static String titleText(List<Container> stopped) {
        return stopped.size() == 1 ? stopped.getFirst().name() + " 컨테이너가 멈춰 있어요"
                : "컨테이너 " + stopped.size() + "개가 멈춰 있어요";
    }

    /** 하나면 "3일 전 종료 · 종료 코드 0", 여럿이면 이름 나열(세 개까지, 나머지는 개수). */
    static String detailText(List<Container> stopped) {
        if (stopped.size() == 1) {
            String status = ContainerFormat.status(stopped.getFirst().status());
            Matcher m = STOPPED.matcher(status);
            return m.matches() ? m.group(2) + " 전 종료 · 종료 코드 " + m.group(1) : status;
        }
        String names = String.join(", ", stopped.stream().limit(3).map(Container::name).toList());
        return stopped.size() > 3 ? names + " 외 " + (stopped.size() - 3) + "개" : names;
    }
}
