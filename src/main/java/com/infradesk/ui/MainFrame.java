package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemInfo;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
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

/** Main window: title bar on top, sidebar on the left, server detail on the right. */
public class MainFrame extends JFrame {

    private static final String EMPTY = "empty";
    private static final String DETAIL = "detail";
    private static final String DASHBOARD = "dashboard";
    private static final String TERMINAL = "terminal";

    private final InventoryService service;
    private final TerminalService terminalService;
    private final com.infradesk.alert.AlertService alerts;
    private final com.infradesk.service.UpdateService updates;
    /** Installs updates in place; null when running from Gradle (not a packaged app) or in demo mode. */
    private com.infradesk.service.UpdateInstaller updateInstaller;
    private final com.infradesk.service.ContainerService containerService;
    private final java.util.Map<String, java.util.Map.Entry<java.time.Instant, com.infradesk.ssh.DockerCommands.Listing>> containerCache =
            new java.util.HashMap<>();
    private final com.infradesk.app.LaunchAtLogin launchAtLogin = com.infradesk.app.LaunchAtLogin.forCurrentOs();
    private SettingsDialog settingsDialog;
    private int notifiedUpdateBuild = -1;
    private Timer updateTimer;
    private final CardLayout screens = new CardLayout();
    private final JPanel screenPanel = new JPanel(screens);
    private final TerminalView terminalView;
    private final boolean demoMode;
    private final TitleBar titleBar;
    private final Sidebar sidebar = new Sidebar();
    private final ServerDetailPanel detail = new ServerDetailPanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel emptyTitle = new JLabel();
    private final JLabel emptyHint = new JLabel();

    private final RefreshPolicy policy = new RefreshPolicy(Clock.systemUTC());
    private final Timer pollTimer;
    /** OCI aggregates metrics per minute, so polling faster gains nothing. */
    private final Timer metricsTimer;
    private LiveStats liveStats;
    private java.util.Optional<TrayController> tray = java.util.Optional.empty();
    private java.util.Map<String, Double> lastCpu = java.util.Map.of();
    private LocalTime lastRefreshAt;
    private boolean hiddenNoticeShown;
    /** SSH-read facts per server id, reused for five minutes. */
    private final java.util.Map<String, java.util.Map.Entry<java.time.Instant, com.infradesk.ssh.HostFacts>> factsCache =
            new java.util.HashMap<>();
    private static final java.time.Duration FACTS_TTL = java.time.Duration.ofMinutes(5);
    private final com.infradesk.ui.metrics.LiveController live;
    private boolean dashboardVisible = true;
    private String metricsServerId;
    private com.infradesk.core.ServerStatus metricsServerStatus;
    private boolean cpuLoadedOnce;

    private List<AccountInventory> inventory = List.of();
    private String selectedServerId;
    private boolean refreshing;

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    /** Where the status icon lives, in the words each OS uses. */
    private static final String TRAY_NAME = MAC ? "메뉴 막대" : "알림 영역(트레이)";

    public MainFrame(InventoryService service, TerminalService terminalService,
                     com.infradesk.alert.AlertService alerts, boolean demoMode) {
        this(service, terminalService, alerts, null, demoMode);
    }

    /** @param updates beta update checks, or null to disable them */
    public MainFrame(InventoryService service, TerminalService terminalService,
                     com.infradesk.alert.AlertService alerts, com.infradesk.service.UpdateService updates, boolean demoMode) {
        super("InfraDesk");
        this.service = service;
        this.terminalService = terminalService;
        this.alerts = alerts;
        this.updates = updates;
        this.containerService = new com.infradesk.service.ContainerService(terminalService);
        this.terminalView = new TerminalView(terminalService, this::editSshSettings, this::allServers);
        this.live = new com.infradesk.ui.metrics.LiveController(new LiveHooks(), new SwingTimeout());
        this.demoMode = demoMode;
        this.titleBar = new TitleBar(demoMode);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (tray.isPresent()) {
                    // Keep running in the menu bar; only pause what needs the window.
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
            }

            @Override
            public void windowDeiconified(java.awt.event.WindowEvent e) {
                if (dashboardVisible) {
                    live.resume();
                }
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
        javax.swing.JScrollPane detailScroll = new javax.swing.JScrollPane(detail);
        detailScroll.setBorder(null);
        detailScroll.getViewport().setBackground(Theme.APP_BG);
        detailScroll.getVerticalScrollBar().setUnitIncrement(24);
        content.add(detailScroll, DETAIL);

        JPanel dashboard = new JPanel(new BorderLayout());
        dashboard.setBackground(Theme.APP_BG);
        dashboard.add(sidebar, BorderLayout.WEST);
        dashboard.add(content, BorderLayout.CENTER);
        screenPanel.add(dashboard, DASHBOARD);
        screenPanel.add(terminalView, TERMINAL);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.APP_BG);
        root.add(titleBar, BorderLayout.NORTH);
        root.add(screenPanel, BorderLayout.CENTER);
        setContentPane(root);
        EditMenu.install(this);

        titleBar.backButton().addActionListener(e -> showDashboard());
        terminalView.onCountChange(titleBar::setSessionCount);
        terminalView.onEmpty(this::showDashboard);
        detail.onSsh(this::openSsh);
        detail.containers().onRefresh(() -> findServer(selectedServerId).ifPresent(s -> loadContainers(s, true)));
        detail.containers().onAction(this::containerAction);
        detail.containers().onLogs(c -> findServer(selectedServerId).ifPresent(s ->
                new com.infradesk.ui.containers.ContainerLogsDialog(this, containerService, s, c).setVisible(true)));
        sidebar.onSshSettings(this::editSshSettings);
        sidebar.settingsButton().addActionListener(e -> openSettings());
        alerts.onDeliveryFailure(message -> javax.swing.SwingUtilities.invokeLater(() ->
                com.infradesk.ui.components.Toast.show(this, "디스코드 알림 전송 실패", "알림을 보내지 못했어요",
                        message, Theme.DANGER_TEXT)));

        titleBar.refreshButton().addActionListener(e -> refresh());
        sidebar.onAddAccount(this::addAccount);
        sidebar.onRemoveAccount(this::removeAccount);
        sidebar.onEditAccount(this::editAccount);
        sidebar.onSelect(this::showServer);
        detail.onAction(this::runAction);
        showEmpty();

        installTray();

        pollTimer = new Timer((int) RefreshPolicy.NORMAL_INTERVAL.toMillis(), e -> poll());
        pollTimer.setRepeats(false);
        metricsTimer = new Timer(60_000, e -> metricsTick());
        metricsTimer.start();
        detail.metrics().onLiveToggle(this::toggleLive);
    }

    /** Menu-bar icon; with it, closing the window hides to the menu bar instead of quitting. */
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
                showDashboard();
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

    /** Asks, then downloads → verifies → self-tests → swaps in the new build and restarts. */
    private void offerUpdate(com.infradesk.service.UpdateService.Release release) {
        Optional<String> blocked = updateInstaller == null
                ? Optional.of("설치된 앱이 아니라서(개발 실행) 스스로 바꿀 수 없어요.")
                : updateInstaller.blocker(release);
        if (blocked.isPresent()) {
            int answer = JOptionPane.showConfirmDialog(isShowing() ? this : null,
                    "새 베타 빌드 " + release.build() + "는 앱에서 바로 설치할 수 없어요.\n" + blocked.get()
                            + "\n\n다운로드 페이지를 열까요?",
                    "업데이트", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                openInBrowser(release.pageUrl());
            }
            return;
        }
        int answer = JOptionPane.showConfirmDialog(isShowing() ? this : null,
                "새 베타 빌드 " + release.build() + "를 설치할까요?\n\n"
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
            LOG.info(() -> "Quitting to install build " + release.build());
            quit();
        }).start();
    }

    private void openSettings() {
        settingsDialog = new SettingsDialog(this, alerts, demoMode, updates, launchAtLogin,
                updates == null ? null : () -> checkForUpdate(true));
        settingsDialog.setVisible(true);
        settingsDialog = null;
    }

    /** Starts periodic beta update checks (15 s after start, then every 6 h) when enabled. */
    public void startUpdateChecks() {
        if (updates == null || demoMode) {
            return;
        }
        updateTimer = new Timer((int) java.time.Duration.ofHours(6).toMillis(), e -> {
            if (updates.autoCheck()) {
                checkForUpdate(false);
            }
        });
        updateTimer.setInitialDelay(15_000);
        updateTimer.start();
    }

    /** @param manual started from the settings dialog: report every outcome there */
    private void checkForUpdate(boolean manual) {
        com.infradesk.app.BuildInfo current = com.infradesk.app.BuildInfo.current();
        Async.run(updates::latest, release -> {
            boolean newer = current.isBeta() && release.build() > current.buildNumber();
            LOG.info(() -> "Update check: latest beta build " + release.build() + ", running " + current.display());
            tray.ifPresent(t -> t.setUpdate(newer ? release : null));
            if (newer && notifiedUpdateBuild != release.build() && !manual) {
                notifiedUpdateBuild = release.build();
                com.infradesk.ui.components.Toast.show(this, "업데이트", "새 베타 빌드 " + release.build() + "가 있어요",
                        TRAY_NAME + " 아이콘 → 새 베타 빌드 설치, 또는 설정 → 앱 정보 → 업데이트 확인", Theme.ACCENT);
            }
            if (manual && settingsDialog != null) {
                String message = newer ? "새 베타 빌드 " + release.build() + "가 있어요."
                        : current.isBeta() ? "최신 빌드예요 (빌드 " + current.buildNumber() + ")."
                        : "개발 빌드라 비교하지 않아요. 최신 베타는 빌드 " + release.build() + "예요.";
                settingsDialog.showUpdateResult(message, newer);
                if (newer) {
                    offerUpdate(release);
                }
            }
        }, err -> {
            LOG.log(java.util.logging.Level.INFO, "Update check failed: " + Async.message(err));
            if (manual && settingsDialog != null) {
                settingsDialog.showUpdateResult(Async.message(err), false);
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
        if (dashboardVisible) {
            live.resume();
        }
    }

    /** Closes sessions and exits. From the tray menu, ⌘Q, or closing the window without a tray. */
    private void quit() {
        LOG.info("Quitting");
        live.stop();
        terminalView.closeAll();
        tray.ifPresent(TrayController::remove);
        dispose();
        System.exit(0);
    }

    private MetricsPanel metrics() {
        return detail.metrics();
    }

    /** Every minute: refresh the selected server's history (unless live) and sidebar CPU. */
    private void metricsTick() {
        if (!isShowing()) {
            // Hidden in the menu bar: keep the sidebar/tray CPU and alerts fresh, skip charts.
            if (tray.isPresent()) {
                refreshCpu();
            }
            return;
        }
        findServer(selectedServerId).ifPresent(s -> {
            if (!liveActive()) {
                loadMetrics(s, false);
            }
        });
        refreshCpu();
    }

    private void refreshCpu() {
        List<AccountInventory> snapshot = inventory;
        Async.run(() -> service.currentCpu(snapshot), cpu -> {
            sidebar.setCpu(cpu);
            if (com.infradesk.alert.AlertService.AVAILABLE) {
                alerts.onCpu(cpu);
            }
            lastCpu = cpu;
            tray.ifPresent(t -> t.update(inventory, cpu, lastRefreshAt));
        }, err -> { });
    }

    /** @param showLoading clear the cards first (when switching servers) */
    private void loadMetrics(Server server, boolean showLoading) {
        metricsServerId = server.id();
        metricsServerStatus = server.status();
        boolean running = server.status() == com.infradesk.core.ServerStatus.RUNNING;
        metrics().setLiveEnabled(running, running
                ? "서버 상세를 보는 동안 SSH로 /proc을 2초마다 읽어요"
                : "서버가 실행 중일 때 쓸 수 있어요");
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
        if (showLoading) {
            metrics().showLoading();
            metrics().setStatus("불러오는 중…", false);
        }
        String id = server.id();
        Async.run(() -> service.metrics(account.get(), id), m -> {
            if (id.equals(metricsServerId) && !liveActive()) {
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

    /** The user flipped the live toggle. Asks for SSH settings first if the server has none. */
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

    /** Connects the live controller to SSH and the metrics panel. */
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
        }

        @Override
        public void showStatus(String text, boolean error) {
            metrics().setStatus(text, error);
        }
    }

    /** Auto-off timer on the EDT. */
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

    /** Reloads every account's servers in the background. */
    public void refresh() {
        reload(Set.of(), true);
    }

    /** Timer tick: reload what the policy asks for. */
    private void poll() {
        RefreshPolicy.Plan plan = policy.next(inventory);
        reload(plan.accountIds(), false);
    }

    /**
     * @param accountIds accounts to reload; empty reloads all
     * @param userInitiated whether to surface failures in a dialog (timer failures stay quiet)
     */
    private void reload(Set<String> accountIds, boolean userInitiated) {
        if (refreshing) {
            return;
        }
        pollTimer.stop();
        refreshing = true;
        titleBar.setRefreshing(true);
        sidebar.setLoading(true);
        boolean full = accountIds.isEmpty();
        List<Account> targets = full ? null : inventory.stream().map(AccountInventory::account)
                .filter(a -> accountIds.contains(a.id())).toList();
        Async.run(() -> full ? service.loadAll() : service.load(targets),
                loaded -> setInventory(full ? loaded : merge(loaded)),
                err -> {
                    refreshing = false;
                    titleBar.setRefreshing(false);
                    sidebar.setLoading(false);
                    scheduleNext();
                    if (userInitiated) {
                        JOptionPane.showMessageDialog(this, Async.message(err), "새로고침 실패", JOptionPane.WARNING_MESSAGE);
                    }
                });
    }

    /** Replaces the reloaded accounts in the current inventory, keeping order. */
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
            showTerminal();
            terminalView.openOrSelect(server);
        });
    }

    private void editSshSettings(Server server) {
        if (new SshSettingsDialog(this, terminalService, server).showDialog()) {
            terminalView.reconnect(server.id());
            if (server.id().equals(selectedServerId)) {
                loadFacts(server, true);
                loadContainers(server, true);
            }
        }
    }

    private static final java.time.Duration CONTAINERS_TTL = java.time.Duration.ofSeconds(30);

    /** Docker containers over SSH for the detail section, cached briefly. */
    private void loadContainers(Server server, boolean force) {
        var panel = detail.containers();
        if (server.status() != com.infradesk.core.ServerStatus.RUNNING) {
            panel.showMessage("서버가 실행 중일 때 표시돼요", null);
            return;
        }
        if (!terminalService.isConfigured(server.id())) {
            panel.showMessage("SSH 설정 후 표시돼요", null);
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
                panel.showMessage("컨테이너 정보를 읽지 못했어요", null);
                panel.showError(Async.message(err));
            }
        });
    }

    private void showListing(com.infradesk.ssh.DockerCommands.Listing listing, java.time.Instant at) {
        var panel = detail.containers();
        if (listing.status() != com.infradesk.ssh.DockerCommands.Listing.Status.OK) {
            panel.showMessage(listing.problem(), null);
            return;
        }
        long running = listing.containers().stream().filter(com.infradesk.ssh.Container::isRunning).count();
        panel.showContainers(listing.containers(), "실행 중 " + running + " / " + listing.containers().size() + " · "
                + java.time.LocalTime.ofInstant(at, java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " 기준");
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

    /** Reads uptime/OS/disk/ports over SSH for the detail grid, cached for a few minutes. */
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
            detail.setFacts(cached.getValue());
            return;
        }
        detail.setFactsMessage("불러오는 중…");
        String id = server.id();
        Async.run(() -> terminalService.facts(server, new HostKeyDialog(this)), facts -> {
            factsCache.put(id, java.util.Map.entry(java.time.Instant.now(), facts));
            if (id.equals(selectedServerId)) {
                detail.setFacts(facts);
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

    /** Public so the snapshot tool can render the terminal screen. */
    public void showTerminal() {
        dashboardVisible = false;
        live.pause();
        terminalView.refreshServers();
        screens.show(screenPanel, TERMINAL);
        titleBar.setTerminalMode(true);
        titleBar.setSessionCount(terminalView.sessionCount());
        terminalView.focusActive();
    }

    private void showDashboard() {
        dashboardVisible = true;
        screens.show(screenPanel, DASHBOARD);
        titleBar.setTerminalMode(false);
        live.resume();
    }

    /** Opens a terminal tab for the server without the settings check; for the snapshot tool. */
    public void openTerminalFor(Server server) {
        showTerminal();
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

    /** Applies loaded data. Public so the snapshot tool can inject data synchronously. */
    public void setInventory(List<AccountInventory> loaded) {
        refreshing = false;
        inventory = List.copyOf(loaded);
        if (com.infradesk.alert.AlertService.AVAILABLE) {
            alerts.onInventory(inventory);
        }
        lastRefreshAt = LocalTime.now();
        tray.ifPresent(t -> t.update(inventory, lastCpu, lastRefreshAt));
        titleBar.setRefreshing(false);
        titleBar.markRefreshed(LocalTime.now());
        titleBar.setCounts(inventory.stream().mapToInt(i -> i.servers().size()).sum(), inventory.size());
        sidebar.setLoading(false);
        sidebar.setInventory(inventory);

        Optional<Server> selected = findServer(selectedServerId);
        if (selected.isPresent()) {
            showServer(selected.get());
        } else if (selectedServerId == null) {
            inventory.stream().flatMap(i -> i.servers().stream()).findFirst().ifPresentOrElse(this::showServer, this::showEmpty);
        } else {
            selectedServerId = null;
            showEmpty();
        }
        scheduleNext();
        if (!cpuLoadedOnce && !inventory.isEmpty()) {
            cpuLoadedOnce = true;
            refreshCpu();
        }
    }

    private void showServer(Server server) {
        Optional<Account> account = accountOf(server);
        if (account.isEmpty()) {
            return;
        }
        boolean switched = !server.id().equals(metricsServerId);
        selectedServerId = server.id();
        sidebar.select(server.id());
        detail.show(server, account.get());
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

    private void showEmpty() {
        live.stop();
        metricsServerId = null;
        boolean noAccounts = inventory.isEmpty();
        emptyTitle.setText(noAccounts ? "계정을 추가하세요" : "서버를 선택하세요");
        emptyHint.setText(noAccounts
                ? "왼쪽 아래 '계정 추가'로 클라우드 계정을 등록하면 서버 목록이 표시돼요."
                : "왼쪽 목록에서 서버를 고르면 상세 정보가 표시돼요.");
        cards.show(content, EMPTY);
    }

    private void addAccount() {
        new AddAccountDialog(this, service, demoMode).showDialog().ifPresent(a -> refresh());
    }

    private void editAccount(Account account) {
        new AddAccountDialog(this, service, demoMode, account).showDialog().ifPresent(a -> refresh());
    }

    private void removeAccount(Account account) {
        int answer = JOptionPane.showConfirmDialog(this,
                "'" + account.displayName() + "' 계정을 삭제할까요?\n저장된 API 키도 함께 지워져요. 클라우드의 서버는 그대로 남아요.",
                "계정 삭제", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        Async.run(() -> {
            service.removeAccount(account.id());
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
