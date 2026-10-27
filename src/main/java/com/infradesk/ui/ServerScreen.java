package com.infradesk.ui;

import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.service.ServerAction;
import com.infradesk.ui.components.TabBar;
import com.infradesk.ui.containers.ContainersTab;
import com.infradesk.ui.files.FilesHost;
import com.infradesk.ui.metrics.MetricsPanel;
import com.infradesk.ui.terminal.TerminalView;

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
    private final ContainersTab containersTab = new ContainersTab();
    private final MetricsPanel metrics = new MetricsPanel();
    private final FilesHost files = new FilesHost();
    /** 터미널 탭 본문의 자리. 터미널은 서버를 가로질러 세션을 들고 있어서 {@code MainFrame}이 만들어 넣는다. */
    private final JPanel terminalHost = new JPanel(new BorderLayout());
    private TerminalView terminal;
    /** 지금 화면에 보여 주는 서버. 서버가 바뀌었는지 알아서 이전 서버의 컨테이너 로그 칩을 비우는 데 쓴다. */
    private String shownServerId;
    private Server shownServer;
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
        // 개요, 모니터링, 컨테이너, 파일, 터미널이 지금 동작한다. 로그 탭은 아래 줄에서 준비 중으로 둔다.
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
        body.add(scrolling(overview), TAB_OVERVIEW);
        // 모니터링 탭: 큰 차트가 세로로 쌓여서 창보다 길어지면 스크롤한다. 안쪽 여백은 개요 탭과 같다(좌우 28).
        JPanel monitoring = new JPanel(new BorderLayout());
        monitoring.setOpaque(false);
        monitoring.setBorder(BorderFactory.createEmptyBorder(20, 28, 24, 28));
        monitoring.add(metrics, BorderLayout.NORTH);
        body.add(scrolling(monitoring), TAB_MONITORING);
        // 컨테이너 탭은 스크롤 창에 넣지 않는다: 안의 표와 로그가 각자 스크롤하고 위아래 나눔을 가진다.
        body.add(containersTab, TAB_CONTAINERS);
        // 파일 탭도 스크롤 창에 넣지 않는다: 두 파일 표와 전송 목록이 각자 스크롤한다. 서버마다 따로 유지된다(FilesHost).
        body.add(files, TAB_FILES);
        // 터미널 탭도 스크롤 창에 넣지 않는다: 터미널과 오른쪽 패널이 각자 크기를 채운다.
        terminalHost.setOpaque(false);
        body.add(terminalHost, TAB_TERMINAL);
        add(body, BorderLayout.CENTER);

        tabs.onSelect(id -> {
            cards.show(body, id);
            if (TAB_CONTAINERS.equals(id)) {
                // 컨테이너 탭을 열면 빈 화면 대신 로그가 바로 보이게 한다(이미 고른 것이 있으면 그대로).
                containersTab.logs().ensureSelection();
                containersTab.fitTable();
            }
            if (TAB_FILES.equals(id) && shownServer != null) {
                files.show(shownServer);   // 이 서버의 파일 탭을 처음 열 때만 연결한다
            }
            if (TAB_TERMINAL.equals(id) && terminal != null) {
                terminal.focusActive();   // 열려 있는 세션이 있으면 바로 입력할 수 있게
            }
        });
    }

    /** 본문을 세로 스크롤 창에 담는다. */
    private static JScrollPane scrolling(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        // 뷰포트는 투명으로 두지 않고 배경색으로 칠한다: 투명하면 스크롤할 때 잔상이 남을 수 있다.
        scroll.getViewport().setBackground(Theme.APP_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
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

    public MetricsPanel metrics() {
        return metrics;
    }

    // ── 컨테이너 탭 ─────────────────────────────────────────────

    public com.infradesk.ui.containers.ContainersPanel containers() {
        return containersTab.containers();
    }

    public com.infradesk.ui.containers.ContainerLogView logs() {
        return containersTab.logs();
    }

    // ── 터미널 탭 ───────────────────────────────────────────────

    /** 터미널 탭 본문을 넣는다. 한 번만 부른다. */
    public void setTerminal(TerminalView view) {
        this.terminal = view;
        terminalHost.add(view, BorderLayout.CENTER);
        if (shownServer != null) {
            view.showServer(shownServer);
        }
    }

    /** 터미널 탭을 연다(예: [SSH 열기], 컨테이너의 셸 버튼). */
    public void showTerminalTab() {
        tabs.select(TAB_TERMINAL);
    }

    /** 탭 이름 옆에 이 서버의 터미널 세션 수를 보여 준다. 0이면 숫자를 감춘다. */
    public void setTerminalCount(int count) {
        tabs.setCount(TAB_TERMINAL, count > 0 ? count : null);
    }

    // ── 파일 탭 ─────────────────────────────────────────────────

    public FilesHost files() {
        return files;
    }

    /** 파일 탭을 연다(예: 터미널 화면의 [SFTP 파일] 버튼). */
    public void showFilesTab() {
        tabs.select(TAB_FILES);
        if (shownServer != null) {
            files.show(shownServer);
        }
    }

    /** 컨테이너 탭을 연다(예: 표의 [로그] 버튼을 눌렀을 때). */
    public void showContainersTab() {
        tabs.select(TAB_CONTAINERS);
    }

    /** 모니터링 탭을 연다. */
    public void showMonitoringTab() {
        tabs.select(TAB_MONITORING);
    }

    /** 개요 탭을 연다. */
    public void showOverviewTab() {
        tabs.select(TAB_OVERVIEW);
    }

    /**
     * 컨테이너 목록이 새로 왔다: 로그 패널의 칩과 탭 이름 옆 개수를 갱신한다. 컨테이너 탭이 열려 있고
     * 아직 고른 컨테이너가 없으면 첫 컨테이너의 로그를 바로 보여 준다. 탭이 닫혀 있을 때는 로그를 읽지
     * 않는다(서버를 고를 때마다 불필요한 SSH 조회가 생기지 않게).
     */
    public void setContainers(java.util.List<com.infradesk.ssh.Container> list) {
        containersTab.logs().setContainers(list);
        tabs.setCount(TAB_CONTAINERS, list.size());
        containersTab.fitTable();
        if (TAB_CONTAINERS.equals(tabs.selectedId())) {
            containersTab.logs().ensureSelection();
        }
    }

    /** 컨테이너를 보여 줄 수 없다(서버 꺼짐, SSH 미설정, 읽기 실패): 로그 패널의 칩과 탭 개수를 비운다. */
    public void clearContainers() {
        containersTab.logs().setContainers(java.util.List.of());
        tabs.setCount(TAB_CONTAINERS, null);
    }

    /**
     * 서버 정보를 보여 준다. 개요의 정보 칸과, 모니터링 탭의 디스크 카드가 같은 값을 쓴다.
     *
     * @param readAt 이 값을 서버에서 읽은 시각(저장된 값을 다시 보여 줄 때는 읽었던 시각)
     */
    public void setFacts(com.infradesk.ssh.HostFacts facts, java.time.Instant readAt) {
        overview.setFacts(facts);
        metrics.setDisk(facts.diskUsed(), facts.diskTotal(), readAt);
    }

    public void setFactsMessage(String message) {
        overview.setFactsMessage(message);
        metrics.setDiskMessage(message);
    }

    // ── 둘 다 ───────────────────────────────────────────────────

    /**
     * 서버를 보여 준다. 머리를 먼저 채운다: 직접 연결 기기의 주소를 IP 가림 대상으로 등록하는 일이
     * 머리에 있고, 개요 본문이 그 뒤에 주소를 표시하기 때문이다.
     */
    public void show(Server server, Account account) {
        if (!server.id().equals(shownServerId)) {
            // 다른 서버로 바뀌었다: 새 목록이 올 때까지 이전 서버의 컨테이너 칩과 개수가 남아 있지 않게 비운다.
            shownServerId = server.id();
            clearContainers();
        }
        shownServer = server;
        if (terminal != null) {
            terminal.showServer(server);   // 터미널 탭은 이 서버의 세션만 보여 준다
            setTerminalCount(terminal.sessionCount(server.id()));
        }
        header.show(server, account);
        overview.show(server, account);
        if (TAB_FILES.equals(tabs.selectedId())) {
            files.show(server);   // 파일 탭을 보는 중에 서버를 바꾸거나 상태가 바뀌었다
        }
    }
}
