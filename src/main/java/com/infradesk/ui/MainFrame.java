package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemInfo;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.InventoryService;
import com.infradesk.service.RefreshPolicy;
import com.infradesk.service.ServerAction;

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

    private final InventoryService service;
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

    private List<AccountInventory> inventory = List.of();
    private String selectedServerId;
    private boolean refreshing;

    public MainFrame(InventoryService service, boolean demoMode) {
        super("InfraDesk");
        this.service = service;
        this.demoMode = demoMode;
        this.titleBar = new TitleBar(demoMode);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
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
        content.add(detail, DETAIL);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.APP_BG);
        root.add(titleBar, BorderLayout.NORTH);
        root.add(sidebar, BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);

        titleBar.refreshButton().addActionListener(e -> refresh());
        sidebar.onAddAccount(this::addAccount);
        sidebar.onRemoveAccount(this::removeAccount);
        sidebar.onSelect(this::showServer);
        detail.onAction(this::runAction);
        showEmpty();

        pollTimer = new Timer((int) RefreshPolicy.NORMAL_INTERVAL.toMillis(), e -> poll());
        pollTimer.setRepeats(false);
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
        detail.setBusy(true, action.label() + " 요청을 보내는 중…");
        Async.run(() -> {
            service.control(account, s.id(), action);
            return null;
        }, ignored -> {
            policy.actionSent(account.id());
            detail.setBusy(false, action.label() + " 요청을 보냈어요. 상태를 5초마다 확인해요.");
            reload(Set.of(account.id()), false);
        }, err -> detail.showActionError(Async.message(err)));
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
                        + account.displayName() + " · " + server.publicIpAddress().orElse(server.region()) + "\n\n" + detailText,
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
    }

    private void showServer(Server server) {
        Optional<Account> account = accountOf(server);
        if (account.isEmpty()) {
            return;
        }
        selectedServerId = server.id();
        sidebar.select(server.id());
        detail.show(server, account.get());
        cards.show(content, DETAIL);
    }

    private void showEmpty() {
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
