package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.service.AccountInventory;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.DashedButton;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 왼쪽 열: 서버 검색, 계정 그룹, 계정 추가 / 설정 하단. 처음 폭은 {@link SidebarWidth#DEFAULT}이고 마우스로 바꿀 수 있다.
 * 폭이 {@link SidebarWidth#COMPACT_BELOW}보다 좁아지면 이름만 보이는 모드로 바뀐다.
 */
public class Sidebar extends JPanel {

    private final JTextField search = new JTextField();
    private final JPanel groups = new JPanel();
    private final JButton addAccount = new DashedButton("계정·서버 추가", "plus");
    private Runnable onAddCloud = () -> { };
    private Runnable onAddSsh = () -> { };
    private final JButton settingsButton = Buttons.icon("settings", "설정", 34);
    private final List<ServerListItem> items = new ArrayList<>();

    private List<AccountInventory> inventory = List.of();
    private java.util.Map<String, Double> cpu = java.util.Map.of();
    private String selectedServerId;
    private Consumer<Server> onSelect = s -> { };
    private Consumer<Account> onRemoveAccount = a -> { };
    private Consumer<Server> onSshSettings = s -> { };
    private Consumer<Account> onEditAccount = a -> { };
    private boolean loading;
    /** 이름만 보이는 좁은 모드인지. */
    private boolean compact;
    private final JPanel top = new JPanel(new BorderLayout());
    private Runnable onOverview = () -> { };
    /** 맨 위의 [개요] [활동 기록]. */
    private final SidebarNavItem overviewItem = new SidebarNavItem("개요", "grid", () -> onOverview.run());
    private final SidebarNavItem activityItem = new SidebarNavItem("활동 기록", "clock", () -> { });
    private final JPanel footer = new JPanel(new BorderLayout(8, 0));

    public Sidebar() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(SidebarWidth.DEFAULT, 0));
        // 마우스로 0까지 줄일 수 있어야 "너무 좁으면 닫기"가 동작한다. 최소 폭을 두면 그 아래로는 끌리지 않는다.
        setMinimumSize(new Dimension(0, 0));
        setBackground(Theme.PANEL_BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.DIVIDER));

        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "서버 검색");
        search.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22; margin: 0,4,0,4");
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.getAccessibleContext().setAccessibleName("서버 검색");
        search.setPreferredSize(new Dimension(0, 32));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuild();
            }
        });
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        top.add(search);

        groups.setOpaque(false);
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JPanel groupsTop = new JPanel(new BorderLayout());
        groupsTop.setOpaque(false);
        groupsTop.add(groups, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(groupsTop);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        settingsButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, null);
        settingsButton.putClientProperty(FlatClientProperties.STYLE, "background: #00000000; borderWidth: 1; borderColor: #43454A; hoverBackground: #FFFFFF10");

        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        footer.add(addAccount, BorderLayout.CENTER);
        addAccount.addActionListener(e -> showAddMenu());
        footer.add(settingsButton, BorderLayout.EAST);

        // 개요는 앱의 첫 화면이라 누르면 이쪽으로 돌아온다. 활동 기록은 저장소가 정해지면 만든다.
        JPanel nav = new JPanel();
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(10, 8, 0, 8));
        nav.add(overviewItem);
        nav.add(Box.createVerticalStrut(2));
        // 준비 중(v2): 활동 기록
        activityItem.comingSoon();
        nav.add(activityItem);
        JPanel topStack = new JPanel(new BorderLayout());
        topStack.setOpaque(false);
        topStack.add(nav, BorderLayout.NORTH);
        topStack.add(top, BorderLayout.CENTER);

        add(topStack, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        // 폭이 바뀌면(마우스로 끌 때) 이름만 모드로 바꿀지 다시 정한다. 닫혀서 폭이 0일 때는 건드리지 않는다.
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (getWidth() > 0) {
                    setCompact(SidebarWidth.isCompact(getWidth()));
                }
            }
        });
        rebuild();
    }

    /**
     * 이름만 보이는 좁은 모드를 켜고 끈다. 켜면 서버 행의 CPU%·상태 글자와 계정 헤더의 리전을 숨기고, 하단
     * "계정·서버 추가"는 글자 없이 아이콘만 남긴다(여백도 줄인다).
     */
    void setCompact(boolean compact) {
        if (this.compact == compact) {
            return;
        }
        this.compact = compact;
        top.setBorder(BorderFactory.createEmptyBorder(12, compact ? 8 : 12, 8, compact ? 8 : 12));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(12, compact ? 8 : 12, 12, compact ? 8 : 12)));
        addAccount.setText(compact ? "" : "계정·서버 추가");
        addAccount.setToolTipText(compact ? "계정·서버 추가" : null);
        overviewItem.setCompact(compact);
        activityItem.setCompact(compact);
        rebuild();   // 서버 행과 계정 헤더를 새 모드로 다시 만든다
    }

    boolean isCompact() {
        return compact;
    }

    /** 클라우드 계정(Oracle Cloud). */
    public void onAddAccount(Runnable action) {
        this.onAddCloud = action;
    }

    /** SSH로 직접 닿는 기기. */
    public void onAddSshServer(Runnable action) {
        this.onAddSsh = action;
    }

    private void showAddMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem cloud = new javax.swing.JMenuItem("클라우드 계정 (Oracle Cloud)", Icons.get("cloud", 16));
        cloud.addActionListener(e -> onAddCloud.run());
        javax.swing.JMenuItem ssh = new javax.swing.JMenuItem("직접 연결 서버 (SSH · 맥미니, 집 서버)", Icons.get("terminal", 16));
        ssh.addActionListener(e -> onAddSsh.run());
        menu.add(cloud);
        menu.add(ssh);
        menu.show(addAccount, 0, -menu.getPreferredSize().height - 4);
    }

    public void onSelect(Consumer<Server> listener) {
        this.onSelect = listener;
    }

    public void onSshSettings(Consumer<Server> listener) {
        this.onSshSettings = listener;
    }

    public void onEditAccount(Consumer<Account> listener) {
        this.onEditAccount = listener;
    }

    public void onRemoveAccount(Consumer<Account> listener) {
        this.onRemoveAccount = listener;
    }

    public JButton settingsButton() {
        return settingsButton;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
        if (inventory.isEmpty()) {
            rebuild();
        }
    }

    public void setInventory(List<AccountInventory> inventory) {
        this.inventory = List.copyOf(inventory);
        rebuild();
    }

    /** 서버 id별 최신 CPU. 실행 중인 서버 옆에 표시한다. */
    public void setCpu(java.util.Map<String, Double> cpu) {
        this.cpu = java.util.Map.copyOf(cpu);
        rebuild();
    }

    /** [개요]를 눌렀다. */
    public void onOverview(Runnable listener) {
        this.onOverview = listener;
    }

    /** 개요를 보고 있다: [개요]를 강조하고 서버 행의 선택은 푼다. */
    public void selectOverview() {
        select(null);
    }

    public void select(String serverId) {
        overviewItem.setSelected(serverId == null);
        selectedServerId = serverId;
        for (ServerListItem item : items) {
            item.setSelected(item.server().id().equals(serverId));
        }
    }

    private void rebuild() {
        groups.removeAll();
        items.clear();
        String query = search.getText().strip().toLowerCase(Locale.ROOT);

        if (inventory.isEmpty()) {
            groups.add(Box.createVerticalStrut(40));
            groups.add(centered(loading ? "불러오는 중…" : "<html><center>등록된 계정이 없어요.<br>아래에서 계정을 추가하세요.</center></html>"));
        }
        boolean anyMatch = false;
        for (AccountInventory inv : inventory) {
            List<Server> servers = inv.servers().stream()
                    .filter(s -> query.isEmpty() || s.name().toLowerCase(Locale.ROOT).contains(query))
                    .toList();
            if (!query.isEmpty() && servers.isEmpty()) {
                continue;
            }
            anyMatch = true;
            groups.add(groupHeader(inv));
            if (inv.failed()) {
                groups.add(errorLine(inv.error()));
            } else if (servers.isEmpty()) {
                groups.add(mutedLine("서버가 없어요"));
            }
            for (Server s : servers) {
                ServerListItem item = new ServerListItem(s, cpu.get(s.id()), () -> {
                    select(s.id());
                    onSelect.accept(s);
                }, () -> onSshSettings.accept(s), () -> onEditAccount.accept(inv.account()));
                item.setSelected(s.id().equals(selectedServerId));
                item.setCompact(compact);
                item.setAlignmentX(Component.LEFT_ALIGNMENT);
                items.add(item);
                groups.add(item);
                groups.add(Box.createVerticalStrut(2));
            }
            groups.add(Box.createVerticalStrut(12));
        }
        if (!inventory.isEmpty() && !anyMatch) {
            groups.add(Box.createVerticalStrut(20));
            groups.add(centered("'" + search.getText().strip() + "'와 일치하는 서버가 없어요"));
        }
        groups.revalidate();
        groups.repaint();
    }

    private JPanel groupHeader(AccountInventory inv) {
        Account a = inv.account();
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        JLabel left = small(a.provider().displayName().replace(" Cloud", "") + " · " + a.displayName());
        JLabel right = small(Regions.shortName(a.region()));
        right.setToolTipText(a.region());
        // 이름이 길거나 폭이 좁으면 왼쪽 글자가 "…"로 줄어든다(가운데에 두어야 줄어든다). 좁은 모드에서는 리전을 숨긴다.
        header.add(left, BorderLayout.CENTER);
        if (!compact) {
            header.add(right, BorderLayout.EAST);
        }
        left.setToolTipText(a.provider().displayName() + " · " + a.displayName());

        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("계정 설정…");
        edit.addActionListener(e -> onEditAccount.accept(a));
        JMenuItem remove = new JMenuItem("계정 삭제…");
        remove.addActionListener(e -> onRemoveAccount.accept(a));
        menu.add(edit);
        menu.addSeparator();
        menu.add(remove);
        header.setComponentPopupMenu(menu);
        header.setToolTipText("우클릭: 계정 설정 · 삭제");
        return header;
    }

    private static JLabel small(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT_MUTED);
        l.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        return l;
    }

    private static JLabel errorLine(String message) {
        JLabel l = new JLabel("<html>" + escape(message) + "</html>");
        l.setForeground(Theme.WARNING);
        l.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        l.setBorder(BorderFactory.createEmptyBorder(2, 10, 4, 8));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(240, 80));
        l.setPreferredSize(new Dimension(236, l.getPreferredSize().height * 3));
        // 사이드바를 좁힐 수 있으므로, 고정 폭(236)보다 좁아져도 줄어들 수 있게 한다(안 그러면 잘려서 넘친다).
        l.setMinimumSize(new Dimension(0, 0));
        return l;
    }

    private static JLabel mutedLine(String text) {
        JLabel l = small(text);
        l.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 8));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel centered(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setForeground(Theme.TEXT_MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
