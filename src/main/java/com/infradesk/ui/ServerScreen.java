package com.infradesk.ui;

import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.service.ServerAction;
import com.infradesk.ui.components.TabBar;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * 서버를 골랐을 때 오른쪽에 나오는 화면 전체 (디자인 v2).
 *
 * <pre>
 *  ┌ ServerHeader ─ 이름 · 배지 · [SSH 열기] [재부팅] [정지] [⋯]
 *  ├ TabBar ─────── 개요 / 모니터링 / 컨테이너 / 파일 / 터미널 / 로그
 *  └ 본문 ───────── 선택한 탭의 내용 (스크롤)
 * </pre>
 *
 * <p>머리와 탭 줄은 고정이고, 본문만 탭에 따라 바뀐다. 아래쪽 상태 막대는 서버 화면이 아니라 창 전체의
 * 것이라 {@code MainFrame}이 따로 둔다.
 *
 * <p>{@code MainFrame}은 예전에 {@link ServerDetailPanel} 하나를 상대했다. 그 호출을 그대로 둘 수 있도록
 * 같은 이름의 메서드를 머리와 개요 본문에 나눠 넘겨 준다.
 */
public class ServerScreen extends JPanel {

    /** 탭 id. 탭을 열 때마다 본문 카드의 이름으로도 쓴다. */
    public static final String TAB_OVERVIEW = "overview";
    public static final String TAB_MONITORING = "monitoring";
    public static final String TAB_CONTAINERS = "containers";
    public static final String TAB_FILES = "files";
    public static final String TAB_TERMINAL = "terminal";
    public static final String TAB_LOGS = "logs";

    private final ServerHeader header = new ServerHeader();
    private final TabBar tabs = new TabBar();
    private final ServerDetailPanel overview = new ServerDetailPanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);

    public ServerScreen() {
        super(new BorderLayout());
        setOpaque(false);

        tabs.addTab(TAB_OVERVIEW, "개요");
        tabs.addTab(TAB_MONITORING, "모니터링");
        tabs.addTab(TAB_CONTAINERS, "컨테이너");
        tabs.addTab(TAB_FILES, "파일");
        tabs.addTab(TAB_TERMINAL, "터미널");
        tabs.addTab(TAB_LOGS, "로그");
        // 개요만 지금 동작한다. 나머지 탭은 화면을 하나씩 만들 때(디자인 v2 3~7단계) 아래 줄을 지워 연다.
        // 준비 중(v2): 모니터링, 컨테이너, 파일, 터미널 탭 — 해당 화면이 만들어지면 setComingSoon을 지운다
        tabs.setComingSoon(TAB_MONITORING);
        tabs.setComingSoon(TAB_CONTAINERS);
        tabs.setComingSoon(TAB_FILES);
        tabs.setComingSoon(TAB_TERMINAL);
        // 준비 중(v2): 로그 탭 — 시안에서도 준비 중이다. 로그를 볼 기능이 생기면 연다
        tabs.setComingSoon(TAB_LOGS);
        // 탭 줄 왼쪽 끝을 머리의 글자와 맞춘다: 화면 여백(28) - 탭 안쪽 여백(12) = 16.
        tabs.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));

        header.setBorder(BorderFactory.createEmptyBorder(22, 28, 14, 28));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(tabs, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        // 본문: 탭 id를 이름으로 하는 카드. 지금은 개요 하나뿐이다.
        body.setOpaque(false);
        JScrollPane overviewScroll = new JScrollPane(overview);
        overviewScroll.setBorder(null);
        // 뷰포트는 투명으로 두지 않고 배경색으로 칠한다: 투명하면 스크롤할 때 잔상이 남을 수 있다.
        overviewScroll.getViewport().setBackground(Theme.APP_BG);
        overviewScroll.getVerticalScrollBar().setUnitIncrement(24);
        body.add(overviewScroll, TAB_OVERVIEW);
        add(body, BorderLayout.CENTER);

        tabs.onSelect(id -> cards.show(body, id));
    }

    // ── MainFrame이 부르는 메서드: 머리 ─────────────────────────

    public void onSsh(Runnable listener) {
        header.onSsh(listener);
    }

    public void onAction(Consumer<ServerAction> listener) {
        header.onAction(listener);
    }

    public void setBusy(boolean busy, String message) {
        header.setBusy(busy, message);
    }

    public void showActionError(String message) {
        header.showActionError(message);
    }

    // ── 개요 본문 ───────────────────────────────────────────────

    public com.infradesk.ui.metrics.MetricsPanel metrics() {
        return overview.metrics();
    }

    public com.infradesk.ui.containers.ContainersPanel containers() {
        return overview.containers();
    }

    public void setFacts(com.infradesk.ssh.HostFacts facts) {
        overview.setFacts(facts);
    }

    public void setFactsMessage(String message) {
        overview.setFactsMessage(message);
    }

    // ── 둘 다 ───────────────────────────────────────────────────

    /**
     * 서버를 보여 준다. 머리를 먼저 채운다: 직접 연결 기기의 주소를 IP 가림 대상으로 등록하는 일이
     * 머리에 있고, 개요 본문이 그 뒤에 주소를 표시하기 때문이다.
     */
    public void show(Server server, Account account) {
        header.show(server, account);
        overview.show(server, account);
    }
}
