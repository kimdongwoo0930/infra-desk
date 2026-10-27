package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemInfo;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.InventoryService;
import com.infradesk.service.LiveStats;
import com.infradesk.service.RefreshPolicy;
import com.infradesk.service.ServerAction;
import com.infradesk.service.TerminalService;
import com.infradesk.ui.metrics.MetricsPanel;
import com.infradesk.ui.terminal.HostKeyDialog;
import com.infradesk.ui.terminal.SshSettingsDialog;
import com.infradesk.ui.terminal.TerminalView;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;

/** 메인 창: 위에 제목 표시줄, 왼쪽에 사이드바, 오른쪽에 서버 상세. */
public class MainFrame extends JFrame {

    private static final String EMPTY = "empty";
    private static final String DETAIL = "detail";
    private static final String OVERVIEW = "overview";

    private final InventoryService service;
    private final TerminalService terminalService;
    private final com.infradesk.alert.AlertService alerts;
    private final com.infradesk.service.UpdateService updates;
    /** 업데이트를 제자리에 설치한다. Gradle로 실행 중(패키징된 앱이 아님)이거나 데모 모드이면 null. */
    private com.infradesk.service.UpdateInstaller updateInstaller;
    private final com.infradesk.service.ContainerService containerService;
    /** 개요가 서버 여러 대의 정보를 한 번에 2대씩 읽는다(조회용 연결, 값은 5분 재사용). */
    private final com.infradesk.service.FleetService fleet;
    private final com.infradesk.ui.fleet.FleetScreen fleetScreen = new com.infradesk.ui.fleet.FleetScreen();
    /** 앱의 첫 화면(개요)이 보이는 중인지. */
    private boolean overviewVisible;
    /** 개요에서 읽는 중인 서버 수와, 그 읽기를 구별하는 번호(오래된 읽기의 끝은 세지 않는다). */
    private int fleetPending;
    private int fleetRun;
    private final java.util.Map<String, java.util.Map.Entry<java.time.Instant, com.infradesk.ssh.DockerCommands.Listing>> containerCache =
            new java.util.HashMap<>();
    private final com.infradesk.app.LaunchAtLogin launchAtLogin = com.infradesk.app.LaunchAtLogin.forCurrentOs();
    /** 설정 화면. 메인 화면(사이드바 + 서버 화면)과 번갈아 보인다. */
    private com.infradesk.ui.settings.SettingsScreen settingsScreen;
    private final CardLayout screens = new CardLayout();
    private final JPanel screenSwitch = new JPanel(screens);
    private boolean settingsVisible;
    private static final String MAIN_SCREEN = "main";
    private static final String SETTINGS_SCREEN = "settings";
    /** 이미 토스트로 알린 업데이트의 버전(같은 것을 반복해서 알리지 않는다). */
    private String notifiedUpdate = "";
    private Timer updateTimer;
    private final TerminalView terminalView;
    private final boolean demoMode;
    private final TitleBar titleBar;
    private final Sidebar sidebar = new Sidebar();
    private final ServerScreen detail = new ServerScreen();
    /** 사이드바와 본문을 나누는 가로 분할. 손잡이를 끌어 사이드바 폭을 바꾼다. */
    private javax.swing.JSplitPane sidebarSplit;
    private static final int SIDEBAR_DIVIDER = 5;
    private boolean sidebarCollapsed;
    /** 닫았다 다시 펼 때 돌아갈 폭(쓸 만한 폭일 때만 갱신한다). */
    private int rememberedSidebarWidth = SidebarWidth.DEFAULT;
    /** 코드가 폭을 바꾸는 중이면 true. 사용자가 끈 것과 구별하려는 것이다. */
    private boolean sidebarAdjusting;
    /** 창 맨 아래 상태 막대 (디자인 v2). 서버 화면과 터미널 화면에 모두 보인다. */
    private final com.infradesk.ui.components.StatusBar statusBar = new com.infradesk.ui.components.StatusBar();
    /** 상태 막대의 "실시간 켜짐/꺼짐" 항목. 실시간이 켜지거나 꺼질 때 글자를 바꾼다. */
    private JLabel liveStatus;
    private JLabel transferStatus;
    private JLabel sshStatus;
    /** 접속 풀이 열어 둔 연결을 1초마다 읽어 상태 막대와 설정의 연결 표를 갱신한다(메모리만 읽고 접속하지 않는다). */
    private final Timer connectionTimer = new Timer(1000, e -> updateConnectionStatus());
    private JLabel terminalStatus;
    /** 메트릭(차트)을 새로 읽는 주기. 타이머와 상태 막대 문구가 같은 값을 쓴다. */
    private static final java.time.Duration METRICS_INTERVAL = java.time.Duration.ofMinutes(1);
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel emptyTitle = new JLabel();
    private final JLabel emptyHint = new JLabel();

    private final RefreshPolicy policy = new RefreshPolicy(Clock.systemUTC());
    private final Timer pollTimer;
    /** OCI는 메트릭을 분 단위로 집계하므로 더 자주 조회해도 얻는 것이 없다. */
    private final Timer metricsTimer;
    private LiveStats liveStats;
    private java.util.Optional<TrayController> tray = java.util.Optional.empty();
    private java.util.Map<String, Double> lastCpu = java.util.Map.of();
    /** 직접 연결 서버(클라우드 메트릭이 없다)에 대한 분당 SSH 샘플. */
    private final com.infradesk.service.SshMetricsService sshMetrics;
    private final java.util.Set<String> sampling = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private LocalTime lastRefreshAt;
    private boolean hiddenNoticeShown;
    /** X로 닫아 메뉴 막대에만 있는 상태. */
    private boolean trayHidden;
    /** Dock(작업 표시줄)으로 최소화한 상태. */
    private boolean iconified;
    /** 창이 다시 보일 때 마지막으로 즉시 갱신한 시각. */
    private java.time.Instant lastReturnRefresh = java.time.Instant.EPOCH;
    private static final java.time.Duration RETURN_REFRESH_GAP = java.time.Duration.ofSeconds(10);
    /** 서버 id별로 SSH로 읽은 정보. 5분 동안 재사용한다. */
    private final java.util.Map<String, java.util.Map.Entry<java.time.Instant, com.infradesk.ssh.HostFacts>> factsCache =
            new java.util.HashMap<>();
    private static final java.time.Duration FACTS_TTL = java.time.Duration.ofMinutes(5);
    private final com.infradesk.ui.metrics.LiveController live;
    private String metricsServerId;
    private com.infradesk.core.ServerStatus metricsServerStatus;
    private boolean cpuLoadedOnce;

    private List<AccountInventory> inventory = List.of();
    private String selectedServerId;
    private boolean refreshing;
    private long reloadGeneration;

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    /** 상태 아이콘이 있는 곳을 각 OS가 쓰는 말로. */
    private static final String TRAY_NAME = MAC ? "메뉴 막대" : "알림 영역(트레이)";

    public MainFrame(InventoryService service, TerminalService terminalService,
                     com.infradesk.alert.AlertService alerts, boolean demoMode) {
        this(service, terminalService, alerts, null, demoMode);
    }

    /** @param updates 업데이트 확인. null이면 사용하지 않는다 */
    public MainFrame(InventoryService service, TerminalService terminalService,
                     com.infradesk.alert.AlertService alerts, com.infradesk.service.UpdateService updates, boolean demoMode) {
        super("InfraDesk");
        this.service = service;
        this.terminalService = terminalService;
        this.sshMetrics = new com.infradesk.service.SshMetricsService(terminalService, java.time.Clock.systemUTC());
        this.alerts = alerts;
        this.updates = updates;
        this.containerService = new com.infradesk.service.ContainerService(terminalService);
        this.fleet = new com.infradesk.service.FleetService(terminalService, containerService);
        this.terminalView = new TerminalView(terminalService, this::editSshSettings);
        this.live = new com.infradesk.ui.metrics.LiveController(new LiveHooks(), new SwingTimeout());
        this.demoMode = demoMode;
        this.titleBar = new TitleBar(demoMode);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        // X로 닫아 메뉴 막대에만 있을 때(HIDE_ON_CLOSE)와 Dock으로 최소화했을 때는 SSH 풀에서 조회 연결만 남기고
        // 나머지는 쓰는 대로 닫는다. 측정 주기는 그대로 1분이다(조회 연결이 유지되니 접속 기록이 늘지 않는다).
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                trayHidden = true;
                updateConnectionMode();
            }

            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                trayHidden = false;
                updateConnectionMode();
                refreshOnReturn();
            }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (tray.isPresent()) {
                    // 메뉴 막대에서 계속 실행한다. 창이 필요한 것만 일시 중지한다.
                    live.pause();
                    if (!hiddenNoticeShown) {
                        hiddenNoticeShown = true;
                        tray.get().notice("InfraDesk는 " + TRAY_NAME + "에서 계속 실행 중이에요",
                                "완전히 끄려면 " + TRAY_NAME + " 아이콘 → 종료" + (MAC ? " 또는 ⌘Q" : ""));
                    }
                } else {
                    quit();
                }
            }

            @Override
            public void windowIconified(java.awt.event.WindowEvent e) {
                live.pause();
                iconified = true;
                updateConnectionMode();
            }

            @Override
            public void windowDeiconified(java.awt.event.WindowEvent e) {
                iconified = false;
                updateConnectionMode();
                refreshOnReturn();
                live.resume();
            }
        });
        setMinimumSize(new Dimension(960, 600));
        setSize(1280, 800);
        setLocationRelativeTo(null);

        getRootPane().putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);
        if (SystemInfo.isMacFullWindowContentSupported) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
            getRootPane().putClientProperty(FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING,
                    FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING_MEDIUM);
        }

        content.setBackground(Theme.APP_BG);
        content.add(emptyState(), EMPTY);
        content.add(com.infradesk.ui.fleet.FleetScreen.scrolling(fleetScreen), OVERVIEW);
        content.add(detail, DETAIL);   // 스크롤은 ServerScreen 안의 본문 영역이 가진다(머리와 탭 줄은 고정)

        // 사이드바와 본문 사이의 손잡이를 끌어 폭을 바꾼다. 폭에 따라 이름만 보이거나(SidebarWidth) 자동으로 닫힌다.
        content.setMinimumSize(new Dimension(360, 0));
        sidebarSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, sidebar, content);
        sidebarSplit.setBorder(null);
        sidebarSplit.setBackground(Theme.APP_BG);
        sidebarSplit.setDividerSize(SIDEBAR_DIVIDER);
        sidebarSplit.setContinuousLayout(true);
        sidebarSplit.setResizeWeight(0.0);   // 창 크기가 바뀌면 남는 폭은 본문이 가진다(사이드바 폭은 그대로)
        sidebarSplit.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> onSidebarDivider((Integer) e.getNewValue()));
        titleBar.sidebarButton().addActionListener(e -> toggleSidebar());
        titleBar.setSidebarCollapsed(false);

        // 터미널은 더 이상 따로 있는 화면이 아니라 서버 화면의 "터미널" 탭이다.
        detail.setTerminal(terminalView);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.APP_BG);
        root.add(titleBar, BorderLayout.NORTH);
        // 설정은 모달 창이 아니라 메인 창 안의 화면이다(디자인 v2): 사이드바 + 서버 화면과 번갈아 보인다.
        settingsScreen = new com.infradesk.ui.settings.SettingsScreen(alerts, demoMode, updates, launchAtLogin,
                updates == null ? null : () -> checkForUpdate(true),
                () -> detail.metrics().setAlert(alerts.settings()),   // 알림 기준이 바뀌면 모니터링 탭의 기준선도 바뀐다
                new com.infradesk.ui.settings.ConnectionPage.Source(terminalService::openConnections, this::serverNames,
                        terminalView::sessionCount, new com.infradesk.ui.settings.ConnectionPage.Disconnector() {
                            @Override
                            public boolean disconnect(String serverId, com.infradesk.ssh.Purpose purpose, boolean force) {
                                return terminalService.disconnect(serverId, purpose, force);
                            }

                            @Override
                            public int disconnectAll(boolean force) {
                                return terminalService.disconnectAll(force);
                            }
                        }));
        settingsScreen.onBack(this::closeSettings);
        screenSwitch.add(sidebarSplit, MAIN_SCREEN);
        screenSwitch.add(settingsScreen, SETTINGS_SCREEN);
        root.add(screenSwitch, BorderLayout.CENTER);
        buildStatusBar();
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);
        EditMenu.install(this);
        connectionTimer.start();

        terminalView.onCountChange(this::updateTerminalCounts);
        // 터미널 탭이 비어 있을 때의 [접속]: SSH 설정이 없으면 설정 창을 먼저 띄우고, 접속해서 셸 세션을 연다.
        terminalView.onConnectRequest(this::openSsh);
        terminalView.setContainerSupport(() -> detail.logs().containers(),
                (container, console) -> findServer(selectedServerId).ifPresent(s -> openContainerTerminal(s, container, console)));
        // 파일 탭: 서버마다 연결을 유지한다. SSH 설정이 없으면 탭이 안내하고, 설정을 마치면 다시 시도한다.
        detail.files().onTransfersChanged(this::updateTransferStatus);
        detail.files().init(terminalService, server -> {
            editSshSettings(server);
            detail.files().retry();
        });
        detail.containers().onRefresh(() -> findServer(selectedServerId).ifPresent(s -> loadContainers(s, true)));
        detail.containers().onAction(this::containerAction);
        // 개요의 컨테이너 요약: 컨테이너 탭과 같은 처리(확인 창, 실행)를 쓴다.
        detail.containerSummary().onAction(this::containerAction);
        // 개요의 "확인 필요"(멈춘 컨테이너): 로그를 보거나 시작한다. 컨테이너 탭과 같은 처리를 쓴다.
        detail.attention().onLogs(c -> {
            detail.showContainersTab();
            showLogsOf(c);
        });
        detail.attention().onStart(c -> containerAction(c, com.infradesk.ssh.DockerCommands.Action.START));
        detail.attention().onViewContainers(detail::showContainersTab);
        // 개요 오른쪽 열: SSH 설정, 저장된 명령어(누르면 터미널 탭에서 실행).
        detail.onSshSettings(() -> findServer(selectedServerId).ifPresent(this::editSshSettings));
        detail.onRunSavedCommand(this::runSavedCommand);
        detail.onOverviewShown(this::reloadSavedCommands);
        reloadSavedCommands();
        detail.containerSummary().onShell(c -> findServer(selectedServerId).ifPresent(s -> openContainerTerminal(s, c, null)));
        detail.containerSummary().onLogs(c -> {
            detail.showContainersTab();
            showLogsOf(c);
        });
        detail.containers().onShell((c, console) -> findServer(selectedServerId).ifPresent(s -> openContainerTerminal(s, c, console)));
        // 표에서 행을 클릭하면 아래 로그가 그 컨테이너로 바뀐다(로그 아이콘은 없다). 반대로 로그의 칩을 고르면
        // 표의 행이 따라서 강조된다.
        detail.containers().onSelect(this::showLogsOf);
        detail.logs().onSelected(c -> detail.containers().highlight(c));
        detail.logs().onReload(this::reloadContainerLogs);
        // 로그 패널의 [별도 창으로 열기]: 이 로그 화면 전체(칩, 거르기, 검색, 자동 새로고침)를 새 창에 하나 더 연다.
        detail.logs().onOpenWindow(c -> findServer(selectedServerId).ifPresent(s -> openLogWindow(s, c)));
        sidebar.onSshSettings(this::editSshSettings);
        sidebar.settingsButton().addActionListener(e -> openSettings());
        alerts.onDeliveryFailure(message -> javax.swing.SwingUtilities.invokeLater(() ->
                com.infradesk.ui.components.Toast.show(this, "디스코드 알림 전송 실패", "알림을 보내지 못했어요",
                        message, Theme.DANGER_TEXT)));

        titleBar.refreshButton().addActionListener(e -> refresh());
        sidebar.onAddAccount(this::addAccount);
        sidebar.onAddSshServer(this::addSshServer);
        sidebar.onRemoveAccount(this::removeAccount);
        sidebar.onEditAccount(this::editAccount);
        sidebar.onSelect(this::showServer);
        sidebar.onOverview(this::showOverview);
        // 개요의 카드: 누르면 그 서버, 버튼은 그 서버 화면의 탭으로(접속은 그 탭이 필요할 때 한다)
        fleetScreen.onOpenServer(server -> {
            showServer(server);
            detail.showOverviewTab();
        });
        fleetScreen.onOpenTab(this::openServerTab);
        fleetScreen.onStart(server -> {
            showServer(server);
            runAction(ServerAction.START);
        });
        fleetScreen.onRefresh(() -> readFleet(true));
        detail.onAction(this::runAction);
        showEmpty();

        installTray();

        pollTimer = new Timer((int) RefreshPolicy.NORMAL_INTERVAL.toMillis(), e -> poll());
        pollTimer.setRepeats(false);
        metricsTimer = new Timer((int) METRICS_INTERVAL.toMillis(), e -> metricsTick());
        metricsTimer.start();
        // "알림 기준" 카드: 지금 알림 설정을 보여 주고, [설정에서 바꾸기]로 설정 창을 연다(닫은 뒤 다시 읽는다).
        detail.metrics().setAlert(alerts.settings());
        detail.metrics().onOpenAlertSettings(() -> openSettings(com.infradesk.ui.settings.AlertsPage.ID));
        detail.metrics().setLiveRemaining(() -> live.remaining());
        detail.metrics().onLiveToggle(this::toggleLive);
        // 구간 선택에서 다른 기록 기간(6시간, 24시간, 7일 …)을 골랐다: 그 기간을 읽는다.
        detail.metrics().onRangeChanged(() -> findServer(selectedServerId).ifPresent(s -> loadMetrics(s, true)));
    }

    /** 서버 id → 이름(설정의 연결 표가 서버 이름을 보여 주려고 쓴다). */
    private java.util.Map<String, String> serverNames() {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        for (Server s : allServers()) {
            names.put(s.id(), s.name());
        }
        return names;
    }

    private void updateConnectionStatus() {
        sshStatus.setText(com.infradesk.ui.ConnectionSummary.statusBar(terminalService.openConnections()));
        if (settingsVisible) {
            settingsScreen.refreshConnections();
        }
    }

    /** 상태 막대의 "전송 N개 진행 중 · 63%". 진행 중인 전송이 없으면 숨긴다. */
    private void updateTransferStatus() {
        var progress = detail.files().transferProgress();
        transferStatus.setVisible(progress.active() > 0);
        transferStatus.setText(transferStatusText(progress.active(), progress.percent()));
    }

    /** 진행 정도(%)를 모르는 동안(파일 목록을 읽는 중)에는 개수만 적는다. */
    static String transferStatusText(int active, int percent) {
        return "전송 " + active + "개 진행 중" + (percent >= 0 ? " · " + percent + "%" : "");
    }

    /**
     * 아래 상태 막대의 항목을 채운다. 왼쪽은 "지금 상태", 오른쪽은 "설정과 버전"이다.
     * 읽을 데이터가 있는 항목은 연결하고, 없는 것은 준비 중으로 자리만 둔다.
     */
    private void buildStatusBar() {
        // 준비 중(v2): SSH 연결 수·용도. SessionPool이 지금 열린 연결을 읽어 주는 방법이 아직 없다
        // (docs/design/v2/GAPS.md "먼저 상의해야 하는 것" 5번). 정해지면 이 항목에 연결한다.
        sshStatus = statusBar.addLeft("SSH 연결 없음");
        liveStatus = statusBar.addLeft("실시간 꺼짐");
        // 파일 탭의 전송이 있을 때만 보인다(서버를 바꿔도 이어지는 전송을 어디서든 알 수 있게).
        transferStatus = statusBar.addLeft(" ");
        transferStatus.setVisible(false);
        // 열린 터미널 세션이 있을 때만 보인다(모든 서버의 세션 합계).
        terminalStatus = statusBar.addLeft(" ");
        terminalStatus.setVisible(false);
        // 조회 주기는 상수에서 만든다. 주기를 바꾸면 이 문구도 따라 바뀐다.
        statusBar.addRight("상태 " + RefreshPolicy.NORMAL_INTERVAL.toSeconds() + "초 · 메트릭 "
                + METRICS_INTERVAL.toMinutes() + "분마다");
        statusBar.addRight("v" + com.infradesk.app.BuildInfo.current().version());
    }

    // ── 사이드바 폭 ─────────────────────────────────────────────

    /** 손잡이 위치가 바뀌었다(마우스로 끌었다). 너무 좁으면 닫고, 너무 넓으면 최대 폭으로 맞춘다. */
    private void onSidebarDivider(int location) {
        // 이름만 보일지는 손잡이 위치가 바뀌는 그 자리에서 바로 정한다(크기 변경 이벤트를 기다리지 않는다).
        // 코드가 폭을 바꾼 경우(다시 펼칠 때 등)도 같다. 0은 닫힌 상태라서 건드리지 않는다.
        if (location > 0) {
            sidebar.setCompact(SidebarWidth.isCompact(location));
        }
        if (sidebarAdjusting || location < 0) {
            return;
        }
        if (sidebarCollapsed) {
            // 끌다가 닫힌 뒤에도 눌린 마우스의 끌기 이벤트가 이어서 들어올 수 있다. 닫힌 동안에는 손잡이를 0에 붙여
            // 두어서, 본문이 밀려 나지 않게 한다.
            if (location != 0) {
                setSidebarLocation(0);
            }
            return;
        }
        if (SidebarWidth.shouldCollapse(location)) {
            collapseSidebar();
        } else if (location > SidebarWidth.MAX) {
            setSidebarLocation(SidebarWidth.MAX);
        } else if (location >= SidebarWidth.REMEMBER_MIN) {
            rememberedSidebarWidth = location;
        }
    }

    private void setSidebarLocation(int width) {
        sidebarAdjusting = true;
        try {
            sidebarSplit.setDividerLocation(width);
        } finally {
            sidebarAdjusting = false;
        }
    }

    /** 사이드바를 닫는다: 사이드바와 손잡이가 사라지고 본문이 전체 폭을 쓴다. */
    void collapseSidebar() {
        if (sidebarCollapsed) {
            return;
        }
        sidebarAdjusting = true;
        try {
            sidebarCollapsed = true;
            sidebar.setVisible(false);
            sidebarSplit.setDividerSize(0);
            sidebarSplit.setDividerLocation(0);
        } finally {
            sidebarAdjusting = false;
        }
        titleBar.setSidebarCollapsed(true);
    }

    /** 사이드바를 다시 펼친다: 닫기 전에 쓰던 폭으로 돌아간다(기억한 폭이 없거나 너무 좁았으면 기본 폭). */
    void expandSidebar() {
        if (!sidebarCollapsed) {
            return;
        }
        sidebarAdjusting = true;
        try {
            sidebarCollapsed = false;
            sidebar.setVisible(true);
            sidebarSplit.setDividerSize(SIDEBAR_DIVIDER);
            sidebarSplit.setDividerLocation(SidebarWidth.restoreWidth(rememberedSidebarWidth));
        } finally {
            sidebarAdjusting = false;
        }
        titleBar.setSidebarCollapsed(false);
    }

    private void toggleSidebar() {
        if (sidebarCollapsed) {
            expandSidebar();
        } else {
            collapseSidebar();
        }
    }

    /** 스냅샷 도구가 폭을 바꿔 보려고 쓴다. */
    void setSidebarWidth(int width) {
        setSidebarLocation(width);
    }

    /** 메뉴 막대 아이콘. 이게 있으면 창을 닫을 때 종료하지 않고 메뉴 막대로 숨는다. */
    private void installTray() {
        if (Boolean.getBoolean("infradesk.noTray")) {
            return;
        }
        tray = TrayController.install(new TrayController.Actions() {
            @Override
            public void showWindow() {
                bringToFront();
            }

            @Override
            public void showServer(String serverId) {
                bringToFront();
                findServer(serverId).ifPresent(MainFrame.this::showServer);
            }

            @Override
            public void openSsh(String serverId) {
                showServer(serverId);
                MainFrame.this.openSsh();
            }

            @Override
            public void runAction(String serverId, ServerAction action) {
                showServer(serverId);
                MainFrame.this.runAction(action);
            }

            @Override
            public void refresh() {
                MainFrame.this.refresh();
            }

            @Override
            public void openUpdate(com.infradesk.service.UpdateService.Release release) {
                offerUpdate(release);
            }

            @Override
            public void quit() {
                MainFrame.this.quit();
            }
        }, demoMode);
        setDefaultCloseOperation(tray.isPresent() ? HIDE_ON_CLOSE : EXIT_ON_CLOSE);

        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (desktop.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED)) {
                desktop.addAppEventListener((java.awt.desktop.AppReopenedListener) e ->
                        javax.swing.SwingUtilities.invokeLater(this::bringToFront));
            }
            if (desktop.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler((e, response) -> javax.swing.SwingUtilities.invokeLater(this::quit));
            }
        }
    }

    public boolean hasTray() {
        return tray.isPresent();
    }

    public void setUpdateInstaller(com.infradesk.service.UpdateInstaller installer) {
        this.updateInstaller = installer;
    }

    /** 지난 업데이트의 교체가 포기됐다면(지금은 이전 버전이 다시 시작된 것) 한 번 알린다. */
    private void reportFailedSwap() {
        java.nio.file.Path log = com.infradesk.storage.AppPaths.logDir().resolve("update.log");
        com.infradesk.service.UpdateInstaller.recentSwapFailure(log, java.time.Instant.now()).ifPresent(line -> {
            LOG.warning("Last update was not applied: " + line);
            try {
                java.nio.file.Files.writeString(log, java.time.LocalDateTime.now() + " (reported to the user)\n",
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ignored) {
                // 최악의 경우 안내가 한 번 더 표시된다.
            }
            javax.swing.SwingUtilities.invokeLater(() -> com.infradesk.ui.components.Toast.show(this, "업데이트",
                    "업데이트를 적용하지 못했어요", "지금 버전이 그대로 켜졌어요. 설정 → 앱 정보 → 로그 폴더의 update.log에 이유가 있어요.",
                    Theme.DANGER_TEXT));
        });
    }

    /** 먼저 묻고, 내려받기 → 검증 → 자가 점검 → 새 빌드로 교체 후 재시작한다. */
    private void offerUpdate(com.infradesk.service.UpdateService.Release release) {
        Optional<String> blocked = updateInstaller == null
                ? Optional.of("설치된 앱이 아니라서(개발 실행) 스스로 바꿀 수 없어요.")
                : updateInstaller.blocker(release);
        if (blocked.isPresent()) {
            int answer = JOptionPane.showConfirmDialog(isShowing() ? this : null,
                    release.label() + " 버전은 앱에서 바로 설치할 수 없어요.\n" + blocked.get()
                            + "\n\n다운로드 페이지를 열까요?",
                    "업데이트", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                openInBrowser(release.pageUrl());
            }
            return;
        }
        int answer = JOptionPane.showConfirmDialog(isShowing() ? this : null,
                release.label() + " 버전을 설치할까요?\n\n"
                        + "받은 파일을 확인하고 점검한 뒤 앱이 종료되고, 새 버전으로 바뀌어 다시 열려요.\n"
                        + "열려 있는 SSH 터미널은 닫혀요. 설정과 키는 그대로예요.",
                "업데이트 설치", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        com.infradesk.service.UpdateInstaller installer = updateInstaller;
        new UpdateProgressDialog(isShowing() ? this : null, installer, release, prepared -> {
            try {
                installer.startSwap(prepared);
            } catch (RuntimeException e) {
                installer.discard(prepared);
                LOG.log(java.util.logging.Level.WARNING, "Update swap failed to start", e);
                JOptionPane.showMessageDialog(this, Async.message(e), "업데이트 실패", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LOG.info(() -> "Quitting to install " + release.version());
            quit();
        }).start();
    }

    private void openSettings() {
        openSettings(null);
    }

    /** 설정 화면을 연다. {@code pageId}가 있으면 그 항목으로 바로 간다(예: 모니터링 탭의 "설정에서 바꾸기" → 알림). */
    private void openSettings(String pageId) {
        if (pageId != null) {
            settingsScreen.show(pageId);
        }
        screens.show(screenSwitch, SETTINGS_SCREEN);
        settingsVisible = true;
        settingsScreen.refreshConnections();   // 타이머를 기다리지 않고 열자마자 지금 상태를 보여 준다
    }

    /** "← 개요로 돌아가기": 메인 화면으로. 알림 기준은 설정 화면이 바로 반영하지만 한 번 더 맞춘다. */
    private void closeSettings() {
        screens.show(screenSwitch, MAIN_SCREEN);
        settingsVisible = false;
        detail.metrics().setAlert(alerts.settings());
    }

    /** 켜져 있으면 주기적인 업데이트 확인을 시작한다(시작 15초 뒤, 그다음 6시간마다). */
    public void startUpdateChecks() {
        if (updates == null || demoMode) {
            return;
        }
        reportFailedSwap();
        updateTimer = new Timer((int) java.time.Duration.ofHours(6).toMillis(), e -> {
            if (updates.autoCheck()) {
                checkForUpdate(false);
            }
        });
        updateTimer.setInitialDelay(15_000);
        updateTimer.start();
    }

    /** @param manual 설정 화면에서 시작함: 모든 결과를 거기에 알린다 */
    private void checkForUpdate(boolean manual) {
        com.infradesk.app.BuildInfo current = com.infradesk.app.BuildInfo.current();
        Async.run(updates::latest, release -> {
            boolean newer = current.isRelease() && com.infradesk.service.UpdateService.isNewer(release.version(), current.version());
            LOG.info(() -> "Update check: latest " + release.version() + ", running " + current.display());
            tray.ifPresent(t -> t.setUpdate(newer ? release : null));
            if (newer && !release.version().equals(notifiedUpdate) && !manual) {
                notifiedUpdate = release.version();
                com.infradesk.ui.components.Toast.show(this, "업데이트", release.label() + " 버전이 나왔어요",
                        TRAY_NAME + " 아이콘 → 새 버전 설치, 또는 설정 → 업데이트·정보 → 업데이트 확인", Theme.ACCENT);
            }
            if (manual && settingsScreen != null) {
                String message = newer ? release.label() + " 버전이 나왔어요."
                        : current.isRelease() ? "최신 버전이에요 (" + current.version() + ")."
                        : "개발 빌드라 비교하지 않아요. 최신 릴리스는 " + release.label() + "예요.";
                settingsScreen.showUpdateResult(message, newer);
                if (newer) {
                    offerUpdate(release);
                }
            }
        }, err -> {
            LOG.log(java.util.logging.Level.INFO, "Update check failed: " + Async.message(err));
            if (manual && settingsScreen != null) {
                settingsScreen.showUpdateResult(Async.message(err), false);
            }
        });
    }

    private void openInBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "Could not open " + url, e);
        }
    }

    private void bringToFront() {
        if (!isVisible()) {
            setVisible(true);
        }
        setExtendedState(getExtendedState() & ~ICONIFIED);
        toFront();
        requestFocus();
        live.resume();
    }

    /** 세션을 닫고 종료한다. 트레이 메뉴, ⌘Q, 또는 트레이가 없을 때 창을 닫으면 호출된다. */
    private void quit() {
        int transfers = detail.files().activeTransfers();
        int unsaved = detail.files().unsavedEdits();
        if ((transfers > 0 || unsaved > 0) && JOptionPane.showConfirmDialog(this,
                (transfers > 0 ? "진행 중인 파일 전송이 " + transfers + "개 있어요. 끄면 중단돼요.\n" : "")
                        + (unsaved > 0 ? "아직 서버에 올리지 못한 편집 내용이 " + unsaved + "개 있어요.\n" : "")
                        + "정말 끌까요?", "InfraDesk 종료",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        LOG.info("Quitting");
        live.stop();
        connectionTimer.stop();
        terminalView.closeAll();
        detail.files().closeAll();
        tray.ifPresent(TrayController::remove);
        dispose();
        System.exit(0);
    }

    private MetricsPanel metrics() {
        return detail.metrics();
    }

    /** 매분: 선택한 서버의 기록(실시간이 아니면)과 사이드바 CPU를 새로 고친다. */
    private void metricsTick() {
        if (windowHidden() || !isShowing()) {
            // 메뉴 막대에 숨었거나 최소화했을 때: 사이드바/트레이 CPU와 알림은 최신으로 유지하고 차트는 건너뛴다.
            // 직접 연결 서버는 이때 CPU만 잰다(sampleDirect).
            refreshCpu();
            sampleDirectServers(false);
            return;
        }
        findServer(selectedServerId).ifPresent(s -> {
            if (!liveActive()) {
                loadMetrics(s, false);
            }
        });
        refreshCpu();
        sampleDirectServers(false);
    }

    /**
     * 실행 중이고 SSH가 설정된 모든 직접 연결 서버의 SSH 샘플을 한 번 찍는다.
     *
     * @param onlyNew 아직 샘플이 없는 서버만(목록을 불러온 직후)
     */
    private void sampleDirectServers(boolean onlyNew) {
        for (AccountInventory inv : inventory) {
            if (inv.account().provider().hasCloudMetrics()) {
                continue;
            }
            for (Server s : inv.servers()) {
                // 실시간 모드로 이 서버를 2초마다 읽는 중이면 1분 측정은 화면에 반영되지 않으므로 건너뛴다.
                if (liveActive() && s.id().equals(metricsServerId)) {
                    continue;
                }
                if (s.status() == com.infradesk.core.ServerStatus.RUNNING
                        && (!onlyNew || sshMetrics.metrics(s.id()).cpuPercent().isEmpty())) {
                    sampleDirect(s);
                }
            }
        }
    }

    /** 창이 사용자에게 보이지 않는 상태: X로 메뉴 막대에만 있거나 Dock으로 최소화. 다른 창에 가려진 것은 보이는 것으로 친다. */
    private boolean windowHidden() {
        return trayHidden || iconified;
    }

    /**
     * 숨어 있던 창이 다시 보인다: 그동안 쌓인 낡은 값을 기다리지 않고 바로 새로 고친다. 서버 상태 목록,
     * 사이드바 CPU, 직접 연결 서버의 전체 샘플(CPU·메모리·네트워크), 선택한 서버의 차트를 읽는다.
     * 창을 연달아 여닫아도 쌓이지 않도록 {@link #RETURN_REFRESH_GAP} 안의 반복은 건너뛴다.
     * 접속 제한(SamplingGate)의 "멈춤"은 풀지 않는다. 서버 상세 정보와 컨테이너는 기존 캐시 기간을 따른다.
     */
    private void refreshOnReturn() {
        java.time.Instant now = java.time.Instant.now();
        if (inventory.isEmpty() || java.time.Duration.between(lastReturnRefresh, now).compareTo(RETURN_REFRESH_GAP) < 0) {
            return;
        }
        lastReturnRefresh = now;
        reload(Set.of(), false);
        refreshCpu();
        sampleDirectServers(false);
        findServer(selectedServerId).ifPresent(s -> {
            if (!liveActive()) {
                loadMetrics(s, false);
            }
        });
    }

    /** 창이 보이지 않을 때(메뉴 막대에만 있거나 최소화) SSH 풀에 알린다. 조회 연결만 남고 나머지는 쓰는 대로 닫힌다. */
    private void updateConnectionMode() {
        terminalService.setBackground(windowHidden());
    }

    private void sampleDirect(Server server) {
        if (!sshMetrics.canSample(server) || !sampling.add(server.id())) {
            return;
        }
        // 창이 보이지 않으면 사이드바·트레이에 필요한 CPU만 잰다.
        boolean cpuOnly = windowHidden();
        Async.run(() -> sshMetrics.sample(server, cpuOnly), sample -> {
            sampling.remove(server.id());
            if (sample.isEmpty()) {
                if (server.id().equals(metricsServerId) && !liveActive()) {
                    Optional<String> stopped = sshMetrics.blockedReason(server.id());
                    boolean noHistory = sshMetrics.metrics(server.id()).cpuPercent().isEmpty();
                    if (stopped.isPresent()) {
                        // 서버 로그에 접속 시도가 쌓이지 않도록 자동 측정을 멈춘 상태다.
                        if (noHistory) {
                            metrics().showMessage("자동 측정을 멈췄어요");
                        }
                        metrics().setStatus(stopped.get() + " · 고친 뒤 새로고침하면 다시 시도해요", true);
                    } else if (noHistory) {
                        metrics().showMessage("SSH로 읽지 못했어요");
                        metrics().setStatus("터미널로 한 번 접속해 호스트 키를 확인했는지 봐 주세요", true);
                    }
                }
                return;
            }
            java.util.Map<String, Double> merged = new java.util.HashMap<>(lastCpu);
            merged.putAll(sshMetrics.latestCpu());
            lastCpu = merged;
            sidebar.setCpu(merged);
            updateFleetView();
            tray.ifPresent(t -> t.update(inventory, merged, lastRefreshAt));
            if (server.id().equals(metricsServerId) && !liveActive()) {
                showDirectHistory(server.id());
            }
        }, err -> sampling.remove(server.id()));
    }

    /** 클라우드 메트릭이 없는 직접 연결 서버인지. */
    private boolean isDirect(Server server) {
        return accountOf(server).map(a -> !a.provider().hasCloudMetrics()).orElse(false);
    }

    private void showDirectHistory(String serverId) {
        metrics().showHistory(sshMetrics.metrics(serverId));
        metrics().setStatus("SSH로 1분마다 수집 · 앱이 켜져 있는 동안의 기록", false);
    }

    private void refreshCpu() {
        List<AccountInventory> snapshot = inventory;
        Async.run(() -> {
            java.util.Map<String, Double> all = new java.util.HashMap<>(service.currentCpu(snapshot));
            all.putAll(sshMetrics.latestCpu());
            return all;
        }, cpu -> {
            sidebar.setCpu(cpu);
            if (com.infradesk.alert.AlertService.AVAILABLE) {
                alerts.onCpu(cpu);
            }
            lastCpu = cpu;
            updateFleetView();
            tray.ifPresent(t -> t.update(inventory, cpu, lastRefreshAt));
        }, err -> { });
    }

    /** @param showLoading 먼저 카드를 비운다(서버를 바꿀 때) */
    private void loadMetrics(Server server, boolean showLoading) {
        metricsServerId = server.id();
        metricsServerStatus = server.status();
        boolean running = server.status() == com.infradesk.core.ServerStatus.RUNNING;
        metrics().setLiveEnabled(running, running ? null : "서버가 실행 중일 때 쓸 수 있어요");
        if (!running) {
            live.stop();
            metrics().showMessage("서버가 실행 중일 때 표시돼요");
            metrics().setStatus(" ", false);
            return;
        }
        Optional<Account> account = accountOf(server);
        if (account.isEmpty()) {
            return;
        }
        // 직접 연결 서버는 앱이 켜져 있는 동안 모은 기록뿐이라 1시간 외의 기간은 고를 수 없다.
        metrics().setHistoryRangesAvailable(account.get().provider().hasCloudMetrics());
        if (!account.get().provider().hasCloudMetrics()) {
            if (liveActive()) {
                return;
            }
            if (!sshMetrics.canSample(server)) {
                metrics().showMessage("SSH 키를 등록하면 CPU·메모리를 볼 수 있어요");
                metrics().setStatus(" ", false);
            } else if (sshMetrics.metrics(server.id()).cpuPercent().isEmpty()) {
                // 첫 샘플은 상세 정보를 읽은 뒤에 온다(호스트 키를 신뢰할지 물을 수 있다).
                metrics().showLoading();
                metrics().setStatus("SSH로 처음 읽는 중…", false);
            } else {
                showDirectHistory(server.id());
            }
            return;
        }
        if (showLoading) {
            metrics().showLoading();
            metrics().setStatus("불러오는 중…", false);
        }
        String id = server.id();
        // 고른 기간을 읽는다. 읽는 사이에 기간을 또 바꿨으면 늦게 도착한 이전 기간의 결과는 버린다.
        java.time.Duration range = metrics().selectedRange();
        Async.run(() -> service.metrics(account.get(), id, range), m -> {
            if (id.equals(metricsServerId) && !liveActive() && range.equals(metrics().selectedRange())) {
                metrics().showHistory(m);
            }
        }, err -> {
            if (id.equals(metricsServerId) && !liveActive()) {
                metrics().showMessage("메트릭을 불러오지 못했어요");
                metrics().setStatus(Async.message(err), true);
            }
        });
    }

    private boolean liveActive() {
        return live.state() != com.infradesk.ui.metrics.LiveController.State.OFF;
    }

    /** 사용자가 실시간 토글을 켰다. 서버에 SSH 설정이 없으면 먼저 설정을 묻는다. */
    private void toggleLive(boolean on) {
        if (!on) {
            live.userToggle(false);
            findServer(selectedServerId).ifPresent(s -> loadMetrics(s, true));
            return;
        }
        Optional<Server> selected = findServer(selectedServerId);
        if (selected.isEmpty()
                || (!terminalService.isConfigured(selected.get().id())
                    && !new SshSettingsDialog(this, terminalService, selected.get()).showDialog())) {
            metrics().setLiveSelected(false);
            return;
        }
        live.userToggle(true);
    }

    /** 실시간 컨트롤러를 SSH와 메트릭 패널에 연결한다. */
    private final class LiveHooks implements com.infradesk.ui.metrics.LiveController.Hooks {

        @Override
        public void open() {
            Optional<Server> selected = findServer(selectedServerId);
            if (selected.isEmpty()) {
                live.failed("서버를 찾을 수 없어요");
                return;
            }
            Server server = selected.get();
            String id = server.id();
            Async.run(() -> terminalService.openStats(server, new HostKeyDialog(MainFrame.this)), session -> {
                if (!id.equals(selectedServerId) || !live.opened()) {
                    session.close();
                    return;
                }
                metrics().clearLive();
                metrics().setStatus("SSH 실시간 · 첫 값을 기다리는 중…", false);
                LiveStats[] self = new LiveStats[1];
                self[0] = LiveStats.start(session, Clock.systemUTC(),
                        sample -> javax.swing.SwingUtilities.invokeLater(() -> {
                            if (liveStats == self[0] && liveStats != null) {
                                metrics().addLive(sample);
                            }
                        }),
                        reason -> javax.swing.SwingUtilities.invokeLater(() -> {
                            if (liveStats == self[0] && liveStats != null) {
                                liveStats = null;
                                live.failed(reason);
                            }
                        }));
                liveStats = self[0];
            }, err -> live.failed(Async.message(err)));
        }

        @Override
        public void close() {
            if (liveStats != null) {
                liveStats.close();
                liveStats = null;
            }
        }

        @Override
        public void showToggle(boolean on) {
            metrics().setLiveSelected(on);
            if (liveStatus != null) {
                liveStatus.setText(on ? "실시간 켜짐" + findServer(selectedServerId).map(s -> " · " + s.name()).orElse("")
                        : "실시간 꺼짐");
            }
        }

        @Override
        public void showStatus(String text, boolean error) {
            metrics().setStatus(text, error);
        }
    }

    /** EDT에서 도는 자동 끄기 타이머. */
    private static final class SwingTimeout implements com.infradesk.ui.metrics.LiveController.Timeout {
        private Timer timer;

        @Override
        public void start(java.time.Duration delay, Runnable onExpire) {
            cancel();
            timer = new Timer((int) delay.toMillis(), e -> onExpire.run());
            timer.setRepeats(false);
            timer.start();
        }

        @Override
        public void cancel() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
        }
    }

    /** 모든 계정의 서버를 백그라운드에서 다시 불러온다. */
    public void refresh() {
        reload(Set.of(), true);
    }

    /** 타이머 틱: 정책이 요청한 것을 다시 불러온다. */
    private void poll() {
        RefreshPolicy.Plan plan = policy.next(inventory);
        reload(plan.accountIds(), false);
    }

    /**
     * @param accountIds 다시 불러올 계정. 비어 있으면 전부
     * @param userInitiated 실패를 다이얼로그로 알릴지(타이머의 실패는 조용히 넘어간다)
     */
    private void reload(Set<String> accountIds, boolean userInitiated) {
        if (refreshing && !userInitiated) {
            return;
        }
        if (userInitiated) {
            // 사용자가 직접 새로고침했다. 설정 문제로 멈춰 둔 직접 연결 서버의 자동 측정도 다시 시도한다.
            sshMetrics.resetAll();
        }
        // 수동 새로고침은 이전 요청이 멈춰 있더라도 항상 새로 시작한다. 늦게 도착한 이전 결과는 버린다.
        long generation = ++reloadGeneration;
        pollTimer.stop();
        refreshing = true;
        titleBar.setRefreshing(true);
        sidebar.setLoading(true);
        boolean full = accountIds.isEmpty();
        List<Account> targets = full ? null : inventory.stream().map(AccountInventory::account)
                .filter(a -> accountIds.contains(a.id())).toList();
        Async.run(() -> full ? service.loadAll() : service.load(targets),
                loaded -> {
                    if (generation == reloadGeneration) {
                        setInventory(full ? loaded : merge(loaded));
                    }
                },
                err -> {
                    if (generation != reloadGeneration) {
                        return;
                    }
                    refreshing = false;
                    titleBar.setRefreshing(false);
                    sidebar.setLoading(false);
                    scheduleNext();
                    if (userInitiated) {
                        JOptionPane.showMessageDialog(this, Async.message(err), "새로고침 실패", JOptionPane.WARNING_MESSAGE);
                    }
                });
    }

    /** 연결할 수 없는 계정의 마지막으로 알려진 서버: 목록에는 남기되 상태는 단정하지 않는다. */
    private static Server asUnreachable(Server s) {
        return s.withStatus(ServerStatus.UNREACHABLE);
    }

    /**
     * 불러오기 실패(예: 잠깐의 네트워크 끊김)가 서버 목록을 지우면 안 된다. 마지막으로 알려진
     * 서버를 연결할 수 없음으로 표시해서 남기고 오류를 붙인다. 연결이 돌아오면 다음 폴링이 이를 대체한다.
     */
    private List<AccountInventory> keepLastKnownServers(List<AccountInventory> loaded) {
        List<AccountInventory> result = new ArrayList<>();
        for (AccountInventory inv : loaded) {
            AccountInventory kept = inv;
            if (inv.failed() && inv.servers().isEmpty()) {
                kept = inventory.stream()
                        .filter(old -> old.account().id().equals(inv.account().id()) && !old.servers().isEmpty())
                        .findFirst()
                        .map(old -> new AccountInventory(inv.account(), old.servers().stream().map(MainFrame::asUnreachable).toList(), inv.error()))
                        .orElse(inv);
            }
            result.add(kept);
        }
        return List.copyOf(result);
    }

    /** 다시 불러온 계정을 현재 인벤토리에서 교체한다. 순서는 유지한다. */
    private List<AccountInventory> merge(List<AccountInventory> partial) {
        List<AccountInventory> merged = new ArrayList<>(inventory);
        for (AccountInventory p : partial) {
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).account().id().equals(p.account().id())) {
                    merged.set(i, p);
                }
            }
        }
        return merged;
    }

    private void scheduleNext() {
        RefreshPolicy.Plan plan = policy.next(inventory);
        pollTimer.setInitialDelay((int) plan.delay().toMillis());
        pollTimer.restart();
    }

    private void openSsh() {
        findServer(selectedServerId).ifPresent(server -> {
            if (!terminalService.isConfigured(server.id())) {
                if (!new SshSettingsDialog(this, terminalService, server).showDialog()) {
                    return;
                }
                loadFacts(server, true);
                loadContainers(server, true);
            }
            detail.showTerminalTab();
            terminalView.openOrSelect(server);
        });
    }

    private void editSshSettings(Server server) {
        // 직접 연결 기기: 포트와, 사용자로는 이 컴퓨터에서의 이름을 추천한다.
        com.infradesk.ssh.SshSettings defaults = accountOf(server)
                .filter(a -> a.provider() == com.infradesk.core.ProviderType.SSH)
                .map(a -> new com.infradesk.ssh.SshSettings(server.id(), System.getProperty("user.name"),
                        com.infradesk.core.SshHostProperties.port(a)))
                .orElse(null);
        if (new SshSettingsDialog(this, terminalService, server, defaults).showDialog()) {
            sshMetrics.reset(server.id());
            terminalView.reconnect(server.id());
            if (server.id().equals(selectedServerId)) {
                detail.setSshUser(sshUserOf(server));   // 개요 정보 카드의 SSH 줄
                loadFacts(server, true);
                loadContainers(server, true);
            }
        }
    }

    private static final java.time.Duration CONTAINERS_TTL = java.time.Duration.ofSeconds(30);

    /** 상세 섹션용으로 SSH로 가져온 Docker 컨테이너. 잠깐 캐시한다. */
    private void loadContainers(Server server, boolean force) {
        var panel = detail.containers();
        if (server.status() != com.infradesk.core.ServerStatus.RUNNING) {
            containersUnavailable("서버가 실행 중일 때 표시돼요");
            return;
        }
        if (!terminalService.isConfigured(server.id())) {
            containersUnavailable("SSH 설정 후 표시돼요");
            return;
        }
        var cached = containerCache.get(server.id());
        if (!force && cached != null && cached.getKey().plus(CONTAINERS_TTL).isAfter(java.time.Instant.now())) {
            showListing(cached.getValue(), cached.getKey());
            return;
        }
        panel.showLoading();
        String id = server.id();
        Async.run(() -> containerService.list(server, new HostKeyDialog(this)), listing -> {
            var at = java.time.Instant.now();
            containerCache.put(id, java.util.Map.entry(at, listing));
            if (id.equals(selectedServerId)) {
                showListing(listing, at);
            }
        }, err -> {
            if (id.equals(selectedServerId)) {
                containersUnavailable("컨테이너 정보를 읽지 못했어요");
                panel.showError(Async.message(err));
            }
        });
    }

    private void showListing(com.infradesk.ssh.DockerCommands.Listing listing, java.time.Instant at) {
        var panel = detail.containers();
        if (listing.status() != com.infradesk.ssh.DockerCommands.Listing.Status.OK) {
            containersUnavailable(listing.problem());
            return;
        }
        // 실행 중/멈춤 개수는 표 위의 칩이 보여 준다. 여기에는 언제 읽은 값인지만 적는다.
        // "30초마다"라고 적지 않는다: 목록은 주기적으로 읽지 않고, 서버를 고를 때(30초 캐시)와 새로고침·동작 뒤에 읽는다.
        panel.showContainers(listing.containers(), "docker ps · "
                + java.time.LocalTime.ofInstant(at, java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " 기준");
        // 같은 목록으로 로그 패널의 컨테이너 칩과 탭 이름 옆 개수도 갱신한다.
        detail.setContainers(listing.containers(), at);
    }

    /**
     * 컨테이너를 보여 줄 수 없을 때(서버가 꺼져 있음, SSH 미설정, Docker 없음, 읽기 실패):
     * 표에는 이유를 적고, 로그 패널의 칩과 탭 개수는 비운다.
     */
    private void containersUnavailable(String reason) {
        detail.containers().showMessage(reason, null);
        detail.clearContainers(reason);
    }

    /**
     * 컨테이너 탭의 로그 패널이 로그를 요청했다(컨테이너를 골랐거나, 줄 수를 바꿨거나, 새로고침을 눌렀다).
     * SSH 조회 연결로 마지막 {@code tail}줄을 읽어 패널에 돌려준다. 접속 방식은 예전 로그 창과 같다.
     */
    /** 아래 로그 패널이 이 컨테이너를 보게 한다. 이미 보고 있으면 다시 읽지 않는다. */
    private void showLogsOf(com.infradesk.ssh.Container container) {
        var current = detail.logs().selected();
        if (current == null || !current.id().equals(container.id())) {
            detail.logs().select(container);
        }
    }

    private void reloadContainerLogs(com.infradesk.ssh.Container container, int tail) {
        Optional<Server> server = findServer(selectedServerId);
        if (server.isEmpty()) {
            detail.logs().showError("서버를 찾을 수 없어요");
            return;
        }
        loadContainerLogs(detail.logs(), server.get(), container, tail);
    }

    /**
     * {@code server}의 {@code container} 로그를 읽어 {@code view}에 돌려준다. 메인 화면의 로그 패널과 별도 창이
     * 같은 코드를 쓴다(조회용 SSH 연결, 접속 규칙이 같다). 읽는 동안 다른 컨테이너로 바뀌면 늦게 도착한 응답과
     * 오류는 패널이 버린다.
     */
    private void loadContainerLogs(com.infradesk.ui.containers.ContainerLogView view, Server server,
                                   com.infradesk.ssh.Container container, int tail) {
        Async.run(() -> containerService.logs(server, container, tail, new HostKeyDialog(this)),
                out -> view.showLogs(container, out),
                err -> {
                    var shown = view.selected();
                    if (shown != null && shown.id().equals(container.id())) {
                        view.showError(Async.message(err));
                    }
                });
    }

    /**
     * 로그를 별도 창으로 연다. 창은 {@code ContainerLogView}를 새로 하나 가져서 메인 화면과 선택·거르기가
     * 독립이다. 창을 연 순간의 컨테이너 목록으로 시작하고(이후 목록 변화는 따라가지 않는다), 지금 보던 컨테이너가
     * 선택된 채로 열린다.
     */
    com.infradesk.ui.containers.ContainerLogWindow openLogWindow(Server server, com.infradesk.ssh.Container current) {
        var view = new com.infradesk.ui.containers.ContainerLogView();
        view.onReload((c, tail) -> loadContainerLogs(view, server, c, tail));
        view.setContainers(detail.logs().containers());
        view.select(current);
        var window = new com.infradesk.ui.containers.ContainerLogWindow(this, server.name(), view);
        window.setVisible(true);
        return window;
    }

    /** 스냅샷 도구가 서버 화면의 탭을 바꿔 보려고 쓴다. */
    ServerScreen serverScreen() {
        return detail;
    }

    private void containerAction(com.infradesk.ssh.Container c, com.infradesk.ssh.DockerCommands.Action action) {
        Optional<Server> server = findServer(selectedServerId);
        if (server.isEmpty()) {
            return;
        }
        String label = switch (action) {
            case START -> "시작";
            case STOP -> "정지";
            case RESTART -> "재시작";
        };
        if (action != com.infradesk.ssh.DockerCommands.Action.START) {
            Object[] options = {label, "취소"};
            int choice = JOptionPane.showOptionDialog(this,
                    "'" + c.name() + "' 컨테이너를 " + label + "할까요?\n" + server.get().name() + " · " + c.image(),
                    "컨테이너 " + label, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (choice != 0) {
                return;
            }
        }
        var panel = detail.containers();
        if (panel.isBusy()) {
            return;   // 앞의 요청이 끝나기 전에 개요와 컨테이너 탭에서 또 누른 경우
        }
        panel.setBusy(true, c.name() + " " + label + " 중…");
        Server s = server.get();
        Async.run(() -> {
            containerService.act(s, c, action, new HostKeyDialog(this));
            return null;
        }, ignored -> loadContainers(s, true), err -> {
            LOG.log(java.util.logging.Level.WARNING, "docker " + action + " failed for " + c.name(), err);
            panel.showError(Async.message(err));
        });
    }

    /** 상세 그리드용으로 SSH로 업타임/OS/디스크/포트를 읽는다. 몇 분 동안 캐시한다. */
    private void loadFacts(Server server, boolean force) {
        if (server.status() != com.infradesk.core.ServerStatus.RUNNING) {
            detail.setFactsMessage("—");
            return;
        }
        if (!terminalService.isConfigured(server.id())) {
            detail.setFactsMessage("SSH 설정 후 표시");
            return;
        }
        var cached = factsCache.get(server.id());
        if (!force && cached != null && cached.getKey().plus(FACTS_TTL).isAfter(java.time.Instant.now())) {
            detail.setFacts(cached.getValue(), cached.getKey());   // 저장된 값은 읽었던 시각과 함께
            return;
        }
        detail.setFactsMessage("불러오는 중…");
        String id = server.id();
        Async.run(() -> terminalService.facts(server, new HostKeyDialog(this)), facts -> {
            // 사용자가 연 화면에서 SSH 접속이 됐다. 자동 측정이 멈춰 있었다면 다시 시도한다.
            sshMetrics.reset(id);
            java.time.Instant readAt = java.time.Instant.now();
            factsCache.put(id, java.util.Map.entry(readAt, facts));
            if (id.equals(selectedServerId)) {
                detail.setFacts(facts, readAt);
            }
            // 상세 정보를 읽는 과정에서 호스트 키를 방금 신뢰했을 수 있다. 백그라운드 샘플링은 절대 묻지 않는다.
            if (isDirect(server) && sshMetrics.metrics(id).cpuPercent().isEmpty()) {
                sampleDirect(server);
            }
        }, err -> {
            if (id.equals(selectedServerId)) {
                detail.setFactsMessage("SSH로 읽지 못함");
            }
        });
    }

    private List<Server> allServers() {
        return inventory.stream().flatMap(i -> i.servers().stream()).toList();
    }

    /**
     * 서버 화면의 터미널 탭을 연다. 열려는 서버가 지금 보는 서버가 아니면 먼저 그 서버를 보여 준다(터미널 탭은 보고
     * 있는 서버의 세션만 보여 주기 때문이다).
     */
    private void showTerminalOf(Server server) {
        if (!server.id().equals(selectedServerId)) {
            showServer(server);
        }
        detail.showTerminalTab();
    }

    /** 개요의 저장된 명령어 목록을 다시 읽는다(터미널 탭에서 추가·편집했을 수 있다). */
    private void reloadSavedCommands() {
        Async.run(terminalService::savedCommands, detail::setSavedCommands, err -> { });
    }

    /** 개요의 저장된 명령어를 눌렀다: 터미널 탭으로 가서 열린 세션에 보낸다. 세션이 없으면 접속을 안내한다(자동 접속은 하지 않는다). */
    private void runSavedCommand(String command) {
        findServer(selectedServerId).ifPresent(server -> {
            detail.showTerminalTab();
            if (!terminalView.send(command)) {
                JOptionPane.showMessageDialog(this,
                        "이 서버에 연결된 터미널이 없어요.\n터미널 탭의 [접속]으로 접속한 뒤 다시 눌러 주세요.",
                        "저장된 명령어", JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    /** 저장된 SSH 설정의 사용자 이름. 설정이 없으면 null. */
    private String sshUserOf(Server server) {
        return terminalService.settings(server.id()).map(com.infradesk.ssh.SshSettings::username).orElse(null);
    }

    /** 세션 수가 바뀌었다: 터미널 탭 이름 옆 개수(보고 있는 서버)와 상태 막대의 전체 개수를 갱신한다. */
    private void updateTerminalCounts() {
        if (selectedServerId != null) {
            detail.setTerminalCount(terminalView.sessionCount(selectedServerId));
        }
        int total = terminalView.sessionCount();
        terminalStatus.setVisible(total > 0);
        terminalStatus.setText("터미널 탭 " + total);
    }

    /** 컨테이너 안의 터미널 탭: 그 컨테이너의 셸, 또는 이미지에 맞는 데이터베이스 콘솔. */
    void openContainerTerminal(Server server, com.infradesk.ssh.Container container,
                                       com.infradesk.ssh.DockerCommands.Console console) {
        String title = console == null ? container.name() : container.name() + " (" + console.label + ")";
        String command = console == null ? com.infradesk.ssh.DockerCommands.shell(container.id())
                : com.infradesk.ssh.DockerCommands.console(container.id(), console);
        LOG.info(() -> "Opening " + (console == null ? "shell" : console.label + " console") + " in container "
                + container.name() + " on " + server.name());
        showTerminalOf(server);
        terminalView.open(server, new com.infradesk.ui.terminal.TerminalPanel.Exec(title, command));
    }

    /** 이 서버에 열려 있는 터미널 세션 수. 스냅샷 도구가 서버별 세션 분리를 확인하는 데 쓴다. */
    int terminalSessions(String serverId) {
        return terminalView.sessionCount(serverId);
    }

    /** 설정 확인 없이 서버의 터미널 탭을 연다. 스냅샷 도구용. */
    public void openTerminalFor(Server server) {
        showTerminalOf(server);
        terminalView.openOrSelect(server);
    }

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MainFrame.class.getName());

    private void runAction(ServerAction action) {
        Optional<Server> server = findServer(selectedServerId);
        if (server.isEmpty()) {
            return;
        }
        Server s = server.get();
        Account account = accountOf(s).orElseThrow();
        if (action.needsConfirmation() && !confirm(action, s, account)) {
            return;
        }
        alerts.expectChange(s.id());
        detail.setBusy(true, action.label() + " 요청을 보내는 중…");
        LOG.info(() -> action + " requested for server " + s.name() + " (account " + account.displayName() + ")");
        Async.run(() -> {
            service.control(account, s.id(), action);
            return null;
        }, ignored -> {
            LOG.info(() -> action + " accepted for server " + s.name());
            policy.actionSent(account.id());
            detail.setBusy(false, action.label() + " 요청을 보냈어요. 상태를 5초마다 확인해요.");
            reload(Set.of(account.id()), false);
        }, err -> {
            LOG.log(java.util.logging.Level.WARNING, action + " failed for server " + s.name(), err);
            detail.showActionError(Async.message(err));
        });
    }

    private boolean confirm(ServerAction action, Server server, Account account) {
        String detailText = switch (action) {
            case STOP -> "서버에서 실행 중인 프로그램이 모두 멈춰요.";
            case REBOOT -> "서버가 다시 시작되는 동안 잠시 연결이 끊겨요.";
            case START -> "";
        };
        Object[] options = {action.label(), "취소"};
        int choice = JOptionPane.showOptionDialog(this,
                "'" + server.name() + "' 서버를 " + action.question() + "\n"
                        + account.displayName() + " · " + server.publicIpAddress().map(IpPrivacy::display).orElse(server.region()) + "\n\n" + detailText,
                "서버 " + action.label(), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return choice == 0;
    }

    private Optional<Account> accountOf(Server server) {
        return inventory.stream()
                .filter(i -> i.account().id().equals(server.accountId()))
                .map(AccountInventory::account)
                .findFirst();
    }

    /** 불러온 데이터를 적용한다. 스냅샷 도구가 데이터를 동기로 주입할 수 있도록 public. */
    public void setInventory(List<AccountInventory> loaded) {
        refreshing = false;
        inventory = keepLastKnownServers(loaded);
        if (com.infradesk.alert.AlertService.AVAILABLE) {
            alerts.onInventory(inventory);
        }
        lastRefreshAt = LocalTime.now();
        tray.ifPresent(t -> t.update(inventory, lastCpu, lastRefreshAt));
        titleBar.setRefreshing(false);
        titleBar.markRefreshed(LocalTime.now());
        titleBar.setCounts(inventory.stream().mapToInt(i -> i.servers().size()).sum(), inventory.size());
        sidebar.setLoading(false);
        for (AccountInventory inv : inventory) {
            if (!inv.account().provider().isCloud()) {
                inv.servers().forEach(s -> IpPrivacy.protect(s.publicIp()));
            }
        }
        sidebar.setInventory(inventory);
        // 목록에서 사라진 서버(삭제한 계정)의 파일 탭은 연결과 함께 닫는다.
        detail.files().retainOnly(allServers().stream().map(Server::id).collect(java.util.stream.Collectors.toSet()));
        sampleDirectServers(true);

        // 주기적으로 목록이 갱신될 때는 설정 화면을 닫지 않는다(서버나 개요를 "고르는" 것이 아니다)
        Optional<Server> selected = findServer(selectedServerId);
        if (selected.isPresent()) {
            showServerView(selected.get());
        } else if (inventory.isEmpty()) {
            selectedServerId = null;
            showEmpty();
        } else {
            showOverviewView();   // 처음이거나, 보던 서버가 목록에서 사라졌다
        }
        scheduleNext();
        if (!cpuLoadedOnce && !inventory.isEmpty()) {
            cpuLoadedOnce = true;
            refreshCpu();
        }
    }

    /** 사용자가 서버를 골랐다(사이드바, 개요의 카드, 트레이). 설정 화면이 열려 있으면 닫고 그 서버를 보여 준다. */
    void showServer(Server server) {
        if (settingsVisible) {
            closeSettings();
        }
        showServerView(server);
    }

    private void showServerView(Server server) {
        Optional<Account> account = accountOf(server);
        if (account.isEmpty()) {
            return;
        }
        if (overviewVisible) {
            overviewVisible = false;
            fleet.cancel();   // 개요를 떠나면 아직 시작하지 않은 서버 읽기는 그만둔다
            fleetPending = 0;
            fleetScreen.setReading(false);
        }
        boolean switched = !server.id().equals(metricsServerId);
        selectedServerId = server.id();
        sidebar.select(server.id());
        detail.show(server, account.get());
        detail.setSshUser(sshUserOf(server));
        cards.show(content, DETAIL);
        if (switched) {
            live.stop();
            loadMetrics(server, true);
            loadFacts(server, false);
            loadContainers(server, false);
        } else if (server.status() != metricsServerStatus) {
            loadMetrics(server, server.status() == com.infradesk.core.ServerStatus.RUNNING);
            loadFacts(server, true);
            loadContainers(server, true);
        }
    }

    /** 개요(앱의 첫 화면)를 보여 준다. 설정 화면이 열려 있으면 닫는다. */
    private void showOverview() {
        if (settingsVisible) {
            closeSettings();
        }
        showOverviewView();
    }

    private void showOverviewView() {
        boolean already = overviewVisible;
        live.stop();
        metricsServerId = null;
        selectedServerId = null;
        overviewVisible = true;
        sidebar.selectOverview();
        cards.show(content, OVERVIEW);
        updateFleetView();
        if (!already) {
            readFleet(false);
        } else if (fleetPending == 0) {
            readFleet(false);   // 목록이 갱신됐다: 새 서버나 오래된 값만 읽는다
        }
    }

    /** 개요의 카드와 요약을 지금 가진 값으로 다시 그린다. */
    private void updateFleetView() {
        if (overviewVisible) {
            fleetScreen.setData(inventory, lastCpu, fleet::latest, terminalService::isConfigured);
        }
    }

    /**
     * 개요에 보이는 서버들의 정보(메모리, 디스크, 업타임, 컨테이너)를 SSH로 읽는다. 한 번에 2대씩이고, 5분 안에 읽은
     * 서버는 건너뛴다. {@code force}면(새로고침) 값이 있어도 다시 읽는다.
     */
    private void readFleet(boolean force) {
        int run = ++fleetRun;
        int started = fleet.read(allServers(), force, reading ->
                javax.swing.SwingUtilities.invokeLater(() -> onFleetReading(reading, run)));
        fleetPending = started;
        fleetScreen.setReading(started > 0);
    }

    private void onFleetReading(com.infradesk.service.FleetService.Reading reading, int run) {
        if (reading.facts() != null) {
            factsCache.put(reading.serverId(), java.util.Map.entry(reading.at(), reading.facts()));   // 서버 화면도 이 값을 쓴다
        }
        updateFleetView();
        if (run != fleetRun) {
            return;   // 그 사이 새 읽기가 시작됐다: 이 읽기의 끝은 세지 않는다
        }
        fleetPending = Math.max(0, fleetPending - 1);
        if (fleetPending == 0) {
            fleetScreen.setReading(false);
            fleetScreen.setReadAt(java.time.LocalTime.now());
        }
    }

    /** 개요 카드의 [SSH] [파일] [모니터링]: 그 서버 화면을 열고 해당 탭으로 간다. 이 동작이 서버에 접속하지는 않는다. */
    private void openServerTab(Server server, String tab) {
        showServer(server);
        switch (tab) {
            case ServerScreen.TAB_TERMINAL -> detail.showTerminalTab();
            case ServerScreen.TAB_FILES -> detail.showFilesTab();
            case ServerScreen.TAB_MONITORING -> detail.showMonitoringTab();
            default -> detail.showOverviewTab();
        }
    }

    private void showEmpty() {
        live.stop();
        metricsServerId = null;
        overviewVisible = false;
        boolean noAccounts = inventory.isEmpty();
        emptyTitle.setText(noAccounts ? "계정을 추가하세요" : "서버를 선택하세요");
        emptyHint.setText(noAccounts
                ? "왼쪽 아래 '계정·서버 추가'로 클라우드 계정이나 직접 연결할 서버를 등록하세요."
                : "왼쪽 목록에서 서버를 고르면 상세 정보가 표시돼요.");
        cards.show(content, EMPTY);
    }

    private void addAccount() {
        new AddAccountDialog(this, service, demoMode).showDialog().ifPresent(a -> refresh());
    }

    private void editAccount(Account account) {
        if (account.provider() == com.infradesk.core.ProviderType.SSH) {
            new AddSshServerDialog(this, service, account).showDialog().ifPresent(a -> refresh());
            return;
        }
        new AddAccountDialog(this, service, demoMode, account).showDialog().ifPresent(a -> refresh());
    }

    /** 직접 연결 기기를 추가한 뒤 바로 SSH 사용자와 키를 묻는다. */
    private void addSshServer() {
        new AddSshServerDialog(this, service, null).showDialog().ifPresent(a -> {
            refresh();
            String host = com.infradesk.core.SshHostProperties.host(a);
            editSshSettings(new Server(com.infradesk.core.SshHostProperties.serverId(a.id()), a.id(), a.displayName(),
                    com.infradesk.core.ServerStatus.UNKNOWN, "SSH", host + ":" + com.infradesk.core.SshHostProperties.port(a),
                    0, 0, host, null, null));
        });
    }

    private void removeAccount(Account account) {
        int answer = JOptionPane.showConfirmDialog(this,
                account.provider().isCloud()
                        ? "'" + account.displayName() + "' 계정을 삭제할까요?\n저장된 API 키도 함께 지워져요. 클라우드의 서버는 그대로 남아요."
                        : "'" + account.displayName() + "'을(를) 목록에서 삭제할까요?\n저장된 SSH 설정과 키도 함께 지워져요. 그 컴퓨터에는 아무 변화가 없어요.",
                "계정 삭제", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        Async.run(() -> {
            service.removeAccount(account.id());
            if (!account.provider().isCloud()) {
                terminalService.forget(com.infradesk.core.SshHostProperties.serverId(account.id()));
                sshMetrics.forget(com.infradesk.core.SshHostProperties.serverId(account.id()));
            }
            return null;
        }, ignored -> refresh(), err -> JOptionPane.showMessageDialog(this, Async.message(err),
                "계정 삭제 실패", JOptionPane.WARNING_MESSAGE));
    }

    private Optional<Server> findServer(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return inventory.stream().flatMap(i -> i.servers().stream()).filter(s -> s.id().equals(id)).findFirst();
    }

    private JPanel emptyState() {
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel icon = new JLabel(Icons.get("cloud", 48, Theme.TEXT_MUTED));
        emptyTitle.setFont(emptyTitle.getFont().deriveFont(Font.BOLD, 22f));
        emptyHint.setForeground(Theme.TEXT_MUTED);

        for (JLabel l : new JLabel[] {icon, emptyTitle, emptyHint}) {
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        box.add(icon);
        box.add(Box.createVerticalStrut(12));
        box.add(emptyTitle);
        box.add(Box.createVerticalStrut(6));
        box.add(emptyHint);
        wrap.add(box);
        return wrap;
    }
}
