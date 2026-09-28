package com.infradesk.ui;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.ServerAction;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * Menu-bar (macOS) / notification-area (Windows) icon with a server-status menu.
 *
 * <p>The menu is described once as a list of {@link Entry} values and rendered per platform:
 * on macOS as a native AWT PopupMenu (a real menu-bar menu), on Windows as a Swing popup
 * ({@link SwingTrayMenu}) because native AWT menus there can't draw Hangul or emoji and show "???".
 *
 * <p>On macOS the icon is a template image (monochrome, follows the light/dark menu bar); on
 * Windows it is drawn light or dark to match the taskbar. Attention is shown with a badge shape;
 * details are in the menu header and tooltip, never in the badge alone.
 */
public final class TrayController {

    /** What the menu can ask the main window to do. */
    public interface Actions {
        void showWindow();

        void showServer(String serverId);

        void openSsh(String serverId);

        void runAction(String serverId, ServerAction action);

        void refresh();

        void openUpdate(com.infradesk.service.UpdateService.Release release);

        void quit();
    }

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final TrayIcon icon;
    private final Actions actions;
    private final boolean demo;
    private final Color glyph = MAC ? Color.BLACK : (SwingTrayMenu.lightTaskbar() ? new Color(0x1E1F22) : Color.WHITE);
    private final Image normalImage = image(false, glyph);
    private final Image attentionImage = image(true, glyph);
    /** Windows only; null on macOS. */
    private final SwingTrayMenu swingMenu;
    private List<Entry> entries = List.of();

    private TrayController(TrayIcon icon, Actions actions, boolean demo) {
        this.icon = icon;
        this.actions = actions;
        this.demo = demo;
        this.swingMenu = MAC ? null : new SwingTrayMenu();
    }

    /** Adds the icon to the menu bar; empty when the platform has no tray. Call on the EDT. */
    public static java.util.Optional<TrayController> install(Actions actions, boolean demo) {
        if (!SystemTray.isSupported()) {
            return java.util.Optional.empty();
        }
        TrayIcon trayIcon = new TrayIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "InfraDesk");
        trayIcon.setImageAutoSize(true);
        TrayController controller = new TrayController(trayIcon, actions, demo);
        trayIcon.setImage(controller.normalImage);
        if (!MAC) {
            // Windows convention: left click opens the app, right click opens the menu.
            trayIcon.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        controller.swingMenu.show(controller.entries);
                    } else if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                        actions.showWindow();
                    }
                }
            });
        }
        controller.update(List.of(), Map.of(), null);
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (java.awt.AWTException e) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(controller);
    }

    /** Short system notification from the menu-bar icon. */
    public void notice(String caption, String text) {
        icon.displayMessage(caption, text, TrayIcon.MessageType.INFO);
    }

    public void remove() {
        SystemTray.getSystemTray().remove(icon);
        if (swingMenu != null) {
            swingMenu.dispose();
        }
    }

    /** Rebuilds the menu and icon from the latest data. Call on the EDT. */
    private List<AccountInventory> lastInventory = List.of();
    private Map<String, Double> lastCpu = Map.of();
    private java.time.LocalTime lastRefreshed;
    private com.infradesk.service.UpdateService.Release update;

    /** Shows (or clears) the "새 베타 빌드 설치" item. Call on the EDT. */
    public void setUpdate(com.infradesk.service.UpdateService.Release release) {
        this.update = release;
        update(lastInventory, lastCpu, lastRefreshed);
    }

    public void update(List<AccountInventory> inventory, Map<String, Double> cpu, java.time.LocalTime refreshedAt) {
        lastInventory = inventory;
        lastCpu = cpu;
        lastRefreshed = refreshedAt;
        Summary s = Summary.of(inventory);
        String updated = refreshedAt == null ? "" : " · " + refreshedAt.format(TIME) + " 갱신";
        icon.setImage(s.needsAttention() ? attentionImage : normalImage);
        icon.setToolTip("InfraDesk" + (demo ? " (데모)" : "") + " · " + s.headline() + updated);
        entries = entries(inventory, cpu, updated, demo, update, actions);
        if (MAC) {
            icon.setPopupMenu(toAwt(entries));
        }
    }

    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    /** Counts for the header, tooltip and badge. Package-private for tests. */
    record Summary(int total, int running, int transitional, int stopped, int failedAccounts) {

        static Summary of(List<AccountInventory> inventory) {
            int total = 0;
            int running = 0;
            int transitional = 0;
            int stopped = 0;
            int failed = 0;
            for (AccountInventory inv : inventory) {
                if (inv.failed()) {
                    failed++;
                }
                for (Server s : inv.servers()) {
                    total++;
                    if (s.status() == ServerStatus.RUNNING) {
                        running++;
                    } else if (s.status().isTransitional()) {
                        transitional++;
                    } else {
                        stopped++;
                    }
                }
            }
            return new Summary(total, running, transitional, stopped, failed);
        }

        boolean needsAttention() {
            return transitional > 0 || failedAccounts > 0;
        }

        String headline() {
            StringBuilder sb = new StringBuilder("실행 중 " + running + " / " + total + "대");
            if (transitional > 0) {
                sb.append(" · 변경 중 ").append(transitional);
            }
            if (failedAccounts > 0) {
                sb.append(" · 계정 오류 ").append(failedAccounts);
            }
            return sb.toString();
        }
    }

    /** One line of the tray menu, independent of how the platform renders it. */
    sealed interface Entry {
        /** Disabled informational line. */
        record Label(String text) implements Entry {
        }

        /** Clickable item; {@code highlight} marks the update offer. */
        record Item(String text, boolean highlight, Runnable action) implements Entry {
        }

        record Separator() implements Entry {
        }

        /** A server with a submenu of the actions its current state allows. */
        record ServerMenu(Server server, Double cpu, List<Entry> items) implements Entry {
        }
    }

    /** Package-private and static so the UI snapshot tool can render the menu without a tray. */
    static List<Entry> entries(List<AccountInventory> inventory, Map<String, Double> cpu, String updated, boolean demo,
                               com.infradesk.service.UpdateService.Release update, Actions actions) {
        List<Entry> menu = new java.util.ArrayList<>();
        menu.add(new Entry.Label("InfraDesk" + (demo ? " (데모)" : "") + " · " + Summary.of(inventory).headline() + updated));
        if (update != null) {
            com.infradesk.service.UpdateService.Release r = update;
            menu.add(new Entry.Item("새 베타 빌드 " + r.build() + " 설치…", true, () -> actions.openUpdate(r)));
        }
        menu.add(new Entry.Separator());

        if (inventory.isEmpty()) {
            menu.add(new Entry.Label("등록된 계정이 없어요"));
        }
        for (AccountInventory inv : inventory) {
            menu.add(new Entry.Label(inv.account().provider().displayName().replace(" Cloud", "") + " · "
                    + inv.account().displayName() + " (" + Regions.shortName(inv.account().region()) + ")"
                    + (inv.failed() ? " — 연결 오류" : "")));
            for (Server s : inv.servers()) {
                menu.add(new Entry.ServerMenu(s, cpu.get(s.id()),
                        serverItems(s, inv.account().provider().hasPowerControl(), actions)));
            }
        }

        menu.add(new Entry.Separator());
        menu.add(new Entry.Item("새로고침", false, actions::refresh));
        menu.add(new Entry.Item("InfraDesk 열기", false, actions::showWindow));
        menu.add(new Entry.Separator());
        menu.add(new Entry.Label("버전 " + com.infradesk.app.BuildInfo.current().display()));
        menu.add(new Entry.Item("종료", false, actions::quit));
        return menu;
    }

    private static List<Entry> serverItems(Server s, boolean powerControl, Actions actions) {
        List<Entry> m = new java.util.ArrayList<>();
        m.add(new Entry.Item("대시보드에서 보기", false, () -> actions.showServer(s.id())));
        if (s.status() == ServerStatus.RUNNING) {
            m.add(new Entry.Item("SSH 열기", false, () -> actions.openSsh(s.id())));
            if (!powerControl) {
                return m;
            }
            m.add(new Entry.Separator());
            m.add(new Entry.Item("재부팅…", false, () -> actions.runAction(s.id(), ServerAction.REBOOT)));
            m.add(new Entry.Item("정지…", false, () -> actions.runAction(s.id(), ServerAction.STOP)));
        } else if (powerControl && s.status().canStart()) {
            m.add(new Entry.Separator());
            m.add(new Entry.Item("시작", false, () -> actions.runAction(s.id(), ServerAction.START)));
        }
        return m;
    }

    /** Native menu for the macOS menu bar; emoji carry the status color there. */
    private static PopupMenu toAwt(List<Entry> entries) {
        PopupMenu menu = new PopupMenu();
        addAwt(menu, entries);
        return menu;
    }

    private static void addAwt(Menu menu, List<Entry> entries) {
        for (Entry e : entries) {
            switch (e) {
                case Entry.Label l -> {
                    MenuItem item = new MenuItem(l.text());
                    item.setEnabled(false);
                    menu.add(item);
                }
                case Entry.Item i -> {
                    MenuItem item = new MenuItem(i.highlight() ? "⬇️  " + i.text() : i.text());
                    item.addActionListener(ev -> javax.swing.SwingUtilities.invokeLater(i.action()));
                    menu.add(item);
                }
                case Entry.Separator ignored -> menu.addSeparator();
                case Entry.ServerMenu sm -> {
                    Menu sub = new Menu(label(sm.server(), sm.cpu()));
                    addAwt(sub, sm.items());
                    menu.add(sub);
                }
            }
        }
    }

    /** "🟢  name  —  CPU 23%"; the emoji and the text both carry the status, so it's never color alone. */
    static String label(Server s, Double cpu) {
        String dot = s.status() == ServerStatus.RUNNING ? "🟢" : s.status().isTransitional() ? "🟡" : "⚪";
        return dot + "  " + plainLabel(s, cpu);
    }

    /** "name  —  CPU 23%" without the emoji (the Swing menu draws a status dot icon instead). */
    static String plainLabel(Server s, Double cpu) {
        String detail = s.status() != ServerStatus.RUNNING ? s.status().label()
                : cpu == null ? "실행 중" : "CPU " + Math.round(cpu) + "%";
        return s.name() + "  —  " + detail;
    }

    /** Server glyph (two stacked racks); the attention variant adds a filled badge top-right. */
    private static Image image(boolean attention, Color color) {
        if (MAC) {
            return new BaseMultiResolutionImage(draw(22, attention, color), draw(44, attention, color));
        }
        // Windows tray slots are 16px at 100% scaling, 24px at 150%, 32px at 200%.
        return new BaseMultiResolutionImage(draw(16, attention, color), draw(20, attention, color),
                draw(24, attention, color), draw(32, attention, color));
    }

    private static BufferedImage draw(int size, boolean attention, Color color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float u = size / 22f;
            g.setColor(color);
            g.setStroke(new BasicStroke(1.6f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Float(3 * u, 4 * u, 15 * u, 6 * u, 3 * u, 3 * u));
            g.draw(new RoundRectangle2D.Float(3 * u, 12 * u, 15 * u, 6 * u, 3 * u, 3 * u));
            g.fill(new Ellipse2D.Float(5.4f * u, 6.2f * u, 1.8f * u, 1.8f * u));
            g.fill(new Ellipse2D.Float(5.4f * u, 14.2f * u, 1.8f * u, 1.8f * u));
            if (attention) {
                // Clear a ring, then fill the badge so it reads as separate from the glyph.
                g.setComposite(java.awt.AlphaComposite.Clear);
                g.fill(new Ellipse2D.Float(13 * u, 0, 9 * u, 9 * u));
                g.setComposite(java.awt.AlphaComposite.SrcOver);
                g.fill(new Ellipse2D.Float(14.5f * u, 1.5f * u, 6 * u, 6 * u));
            }
        } finally {
            g.dispose();
        }
        return img;
    }
}
