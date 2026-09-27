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

/** 260px left column: server search, account groups, and the add-account / settings footer. */
public class Sidebar extends JPanel {

    private final JTextField search = new JTextField();
    private final JPanel groups = new JPanel();
    private final JButton addAccount = new DashedButton("계정 추가", "plus");
    private final JButton settingsButton = Buttons.icon("settings", "설정", 34);
    private final List<ServerListItem> items = new ArrayList<>();

    private List<AccountInventory> inventory = List.of();
    private java.util.Map<String, Double> cpu = java.util.Map.of();
    private String selectedServerId;
    private Consumer<Server> onSelect = s -> { };
    private Consumer<Account> onRemoveAccount = a -> { };
    private Consumer<Server> onSshSettings = s -> { };
    private boolean loading;

    public Sidebar() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(260, 0));
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
        JPanel top = new JPanel(new BorderLayout());
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

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        footer.add(addAccount, BorderLayout.CENTER);
        footer.add(settingsButton, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        rebuild();
    }

    public void onAddAccount(Runnable action) {
        addAccount.addActionListener(e -> action.run());
    }

    public void onSelect(Consumer<Server> listener) {
        this.onSelect = listener;
    }

    public void onSshSettings(Consumer<Server> listener) {
        this.onSshSettings = listener;
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

    /** Latest CPU per server id, shown next to running servers. */
    public void setCpu(java.util.Map<String, Double> cpu) {
        this.cpu = java.util.Map.copyOf(cpu);
        rebuild();
    }

    public void select(String serverId) {
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
                }, () -> onSshSettings.accept(s));
                item.setSelected(s.id().equals(selectedServerId));
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
        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem remove = new JMenuItem("계정 삭제…");
        remove.addActionListener(e -> onRemoveAccount.accept(a));
        menu.add(remove);
        header.setComponentPopupMenu(menu);
        header.setToolTipText("우클릭: 계정 메뉴");
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
