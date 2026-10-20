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
 * 서버 상태 메뉴가 있는 메뉴 막대(macOS) / 알림 영역(Windows) 아이콘.
 *
 * <p>메뉴는 {@link Entry} 값의 목록으로 한 번만 기술하고 플랫폼마다 다르게 그린다:
 * macOS는 네이티브 AWT PopupMenu(진짜 메뉴 막대 메뉴), Windows는 Swing 팝업
 * ({@link SwingTrayMenu})이다. Windows의 네이티브 AWT 메뉴는 한글과 이모지를 그리지 못하고 "???"로 표시하기 때문이다.
 *
 * <p>macOS에서는 아이콘이 템플릿 이미지(단색이며 밝은/어두운 메뉴 막대를 따라감)이고,
 * Windows에서는 작업 표시줄에 맞춰 밝거나 어둡게 그린다. 주의가 필요한 상태는 배지 모양으로 표시하며,
 * 세부 내용은 메뉴 헤더와 툴팁에 있고 배지만으로 전달하지 않는다.
 */
public final class TrayController {

    /** 메뉴가 메인 창에 요청할 수 있는 것. */
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
    /** Windows 전용. macOS에서는 null. */
    private final SwingTrayMenu swingMenu;
    private List<Entry> entries = List.of();

    private TrayController(TrayIcon icon, Actions actions, boolean demo) {
        this.icon = icon;
        this.actions = actions;
        this.demo = demo;
        this.swingMenu = MAC ? null : new SwingTrayMenu();
    }

    /** 아이콘을 메뉴 막대에 추가한다. 플랫폼에 트레이가 없으면 빈 값. EDT에서 호출한다. */
    public static java.util.Optional<TrayController> install(Actions actions, boolean demo) {
        if (!SystemTray.isSupported()) {
            return java.util.Optional.empty();
        }
        TrayIcon trayIcon = new TrayIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "InfraDesk");
        trayIcon.setImageAutoSize(true);
        TrayController controller = new TrayController(trayIcon, actions, demo);
        trayIcon.setImage(controller.normalImage);
        if (!MAC) {
            // Windows 관례: 왼쪽 클릭은 앱을 열고, 오른쪽 클릭은 메뉴를 연다.
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

    /** 메뉴 막대 아이콘에서 보내는 짧은 시스템 알림. */
    public void notice(String caption, String text) {
        icon.displayMessage(caption, text, TrayIcon.MessageType.INFO);
    }

    public void remove() {
        SystemTray.getSystemTray().remove(icon);
        if (swingMenu != null) {
            swingMenu.dispose();
        }
    }

    /** 최신 데이터로 메뉴와 아이콘을 다시 만든다. EDT에서 호출한다. */
    private List<AccountInventory> lastInventory = List.of();
    private Map<String, Double> lastCpu = Map.of();
    private java.time.LocalTime lastRefreshed;
    private com.infradesk.service.UpdateService.Release update;

    /** "새 베타 빌드 설치" 항목을 표시한다(또는 지운다). EDT에서 호출한다. */
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

    /** 헤더, 툴팁, 배지에 쓰는 개수. 테스트를 위해 package-private. */
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

    /** 플랫폼이 어떻게 그리는지와 무관한 트레이 메뉴의 한 줄. */
    sealed interface Entry {
        /** 비활성화된 안내 줄. */
        record Label(String text) implements Entry {
        }

        /** 클릭할 수 있는 항목. {@code highlight}는 업데이트 안내를 표시한다. */
        record Item(String text, boolean highlight, Runnable action) implements Entry {
        }

        record Separator() implements Entry {
        }

        /** 현재 상태에서 허용되는 동작의 하위 메뉴가 있는 서버. */
        record ServerMenu(Server server, Double cpu, List<Entry> items) implements Entry {
        }
    }

    /** 트레이 없이도 UI 스냅샷 도구가 메뉴를 그릴 수 있도록 package-private이고 static이다. */
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

    /** macOS 메뉴 막대용 네이티브 메뉴. 거기서는 이모지가 상태 색을 전달한다. */
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

    /** "🟢  name  —  CPU 23%": 이모지와 텍스트가 모두 상태를 전달하므로 색만으로 구분하지 않는다. */
    static String label(Server s, Double cpu) {
        String dot = s.status() == ServerStatus.RUNNING ? "🟢" : s.status().isTransitional() ? "🟡" : "⚪";
        return dot + "  " + plainLabel(s, cpu);
    }

    /** 이모지가 없는 "name  —  CPU 23%"(Swing 메뉴는 대신 상태 점 아이콘을 그린다). */
    static String plainLabel(Server s, Double cpu) {
        String detail = s.status() != ServerStatus.RUNNING ? s.status().label()
                : cpu == null ? "실행 중" : "CPU " + Math.round(cpu) + "%";
        return s.name() + "  —  " + detail;
    }

    /** 서버 모양(랙 두 개를 쌓은 모양). 주의 변형은 오른쪽 위에 채운 배지가 추가된다. */
    private static Image image(boolean attention, Color color) {
        if (MAC) {
            return new BaseMultiResolutionImage(draw(22, attention, color), draw(44, attention, color));
        }
        // Windows 트레이 칸은 100% 배율에서 16px, 150%에서 24px, 200%에서 32px이다.
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
                // 고리 모양으로 비운 뒤 배지를 채워서 도형과 분리되어 보이게 한다.
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
