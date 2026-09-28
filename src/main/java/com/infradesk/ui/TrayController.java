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
 * macOS menu-bar (system tray) icon with a server-status menu. The menu is a native AWT
 * PopupMenu, so it doesn't go through Swing popups at all.
 *
 * <p>The icon is a template image (monochrome, follows the light/dark menu bar); attention is
 * shown with a badge shape since template images can't carry color. Details are in the menu
 * header and tooltip, never in the badge alone.
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

    private final TrayIcon icon;
    private final Actions actions;
    private final boolean demo;
    private final Image normalImage = image(false);
    private final Image attentionImage = image(true);

    private TrayController(TrayIcon icon, Actions actions, boolean demo) {
        this.icon = icon;
        this.actions = actions;
        this.demo = demo;
    }

    /** Adds the icon to the menu bar; empty when the platform has no tray. Call on the EDT. */
    public static java.util.Optional<TrayController> install(Actions actions, boolean demo) {
        if (!SystemTray.isSupported()) {
            return java.util.Optional.empty();
        }
        TrayIcon trayIcon = new TrayIcon(image(false), "InfraDesk");
        trayIcon.setImageAutoSize(true);
        TrayController controller = new TrayController(trayIcon, actions, demo);
        trayIcon.setPopupMenu(controller.menu(List.of(), Map.of(), ""));
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
    }

    /** Rebuilds the menu and icon from the latest data. Call on the EDT. */
    private List<AccountInventory> lastInventory = List.of();
    private Map<String, Double> lastCpu = Map.of();
    private java.time.LocalTime lastRefreshed;
    private com.infradesk.service.UpdateService.Release update;

    /** Shows (or clears) the "새 베타 빌드 받기" item. Call on the EDT. */
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
        icon.setPopupMenu(menu(inventory, cpu, updated));
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

    private PopupMenu menu(List<AccountInventory> inventory, Map<String, Double> cpu, String updated) {
        PopupMenu menu = new PopupMenu();
        MenuItem header = new MenuItem("InfraDesk" + (demo ? " (데모)" : "") + " · " + Summary.of(inventory).headline() + updated);
        header.setEnabled(false);
        menu.add(header);
        if (update != null) {
            com.infradesk.service.UpdateService.Release r = update;
            menu.add(item("⬇️  새 베타 빌드 " + r.build() + " 받기…", () -> actions.openUpdate(r)));
        }
        menu.addSeparator();

        if (inventory.isEmpty()) {
            MenuItem empty = new MenuItem("등록된 계정이 없어요");
            empty.setEnabled(false);
            menu.add(empty);
        }
        for (AccountInventory inv : inventory) {
            MenuItem account = new MenuItem(inv.account().provider().displayName().replace(" Cloud", "") + " · "
                    + inv.account().displayName() + " (" + Regions.shortName(inv.account().region()) + ")"
                    + (inv.failed() ? " — 연결 오류" : ""));
            account.setEnabled(false);
            menu.add(account);
            for (Server s : inv.servers()) {
                menu.add(serverMenu(s, cpu.get(s.id())));
            }
        }

        menu.addSeparator();
        menu.add(item("새로고침", actions::refresh));
        menu.add(item("InfraDesk 열기", actions::showWindow));
        menu.addSeparator();
        MenuItem version = new MenuItem("버전 " + com.infradesk.app.BuildInfo.current().display());
        version.setEnabled(false);
        menu.add(version);
        menu.add(item("종료", actions::quit));
        return menu;
    }

    /** "🟢 name   23%" with a submenu of actions allowed in the current state. */
    private Menu serverMenu(Server s, Double cpu) {
        Menu m = new Menu(label(s, cpu));
        m.add(item("대시보드에서 보기", () -> actions.showServer(s.id())));
        if (s.status() == ServerStatus.RUNNING) {
            m.add(item("SSH 열기", () -> actions.openSsh(s.id())));
            m.addSeparator();
            m.add(item("재부팅…", () -> actions.runAction(s.id(), ServerAction.REBOOT)));
            m.add(item("정지…", () -> actions.runAction(s.id(), ServerAction.STOP)));
        } else if (s.status().canStart()) {
            m.addSeparator();
            m.add(item("시작", () -> actions.runAction(s.id(), ServerAction.START)));
        }
        return m;
    }

    /** Menu label; the emoji and the text both carry the status, so it's never color alone. */
    static String label(Server s, Double cpu) {
        String dot = s.status() == ServerStatus.RUNNING ? "🟢" : s.status().isTransitional() ? "🟡" : "⚪";
        String detail = s.status() != ServerStatus.RUNNING ? s.status().label()
                : cpu == null ? "실행 중" : "CPU " + Math.round(cpu) + "%";
        return dot + "  " + s.name() + "  —  " + detail;
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> javax.swing.SwingUtilities.invokeLater(action));
        return item;
    }

    /** Server glyph (two stacked racks); the attention variant adds a filled badge top-right. */
    private static Image image(boolean attention) {
        return new BaseMultiResolutionImage(draw(22, attention), draw(44, attention));
    }

    private static BufferedImage draw(int size, boolean attention) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float u = size / 22f;
            g.setColor(Color.BLACK);
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
