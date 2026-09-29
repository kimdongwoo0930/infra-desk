package com.infradesk.ui;

import com.infradesk.app.InfraDeskApp;
import com.infradesk.provider.demo.DemoProviderFactory;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.InventoryService;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/**
 * Dev tool: renders screens off-screen with demo data into build/snapshots/*.png, so UI changes
 * can be checked without screen-recording permission. Run with {@code ./gradlew snapshot}.
 */
public final class UiSnapshot {

    private static final File DIR = new File("build/snapshots");

    private UiSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        DIR.mkdirs();
        InventoryService service = InfraDeskApp.demoService(new DemoProviderFactory(Clock.systemUTC(), Duration.ZERO));
        List<AccountInventory> inventory = service.loadAll();
        // Separate demo world where discord-bot was just stopped, for the "stopping" screen.
        InventoryService stoppingService = InfraDeskApp.demoService(new DemoProviderFactory(Clock.systemUTC(), Duration.ZERO));
        var stoppingInventory = stoppingService.loadAll();
        stoppingService.control(stoppingService.accounts().getFirst(),
                stoppingInventory.getFirst().servers().getFirst().id(), com.infradesk.service.ServerAction.STOP);
        List<AccountInventory> stopping = stoppingService.loadAll();

        MainFrame[] mainFrame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            mainFrame[0] = new MainFrame(service, fastLiveTerminalService(), InfraDeskApp.demoAlertService(), true);
            mainFrame[0].setInventory(inventory);
        });
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> {
            try {
                MainFrame empty = new MainFrame(service, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
                empty.setInventory(List.of());
                write(empty, empty.getContentPane(), 1280, 800, "main-empty.png");

                MainFrame frame = mainFrame[0];
                write(frame, frame.getContentPane(), 1280, 800, "main.png");
                write(frame, frame.getContentPane(), 1280, 1080, "main-full.png");
                frame.getContentPane().setSize(1280, 800);

                // Hover: move the mouse over each chart (left, middle, right edge) and render.
                List<java.awt.Component> charts = new java.util.ArrayList<>();
                collect(frame.getContentPane(), "HoverChartPanel", charts);
                double[] at = {0.3, 0.55, 0.97};
                for (int i = 0; i < charts.size() && i < at.length; i++) {
                    java.awt.Component c = charts.get(i);
                    c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_MOVED,
                            System.currentTimeMillis(), 0, (int) (c.getWidth() * at[i]), 20, 0, false));
                }
                write(frame, frame.getContentPane(), 1280, 800, "main-hover.png");
                for (java.awt.Component c : charts) {
                    c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_EXITED,
                            System.currentTimeMillis(), 0, -1, -1, 0, false));
                }

                MainFrame stoppingFrame = new MainFrame(stoppingService, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
                stoppingFrame.setInventory(stopping);
                write(stoppingFrame, stoppingFrame.getContentPane(), 1280, 800, "main-stopping.png");
                stoppingFrame.dispose();

                JDialog dialog = new AddAccountDialog(frame, service, false);
                write(dialog, dialog.getContentPane(), dialog.getWidth(), dialog.getContentPane().getPreferredSize().height,
                        "add-account.png");
                dialog.dispose();

                // A directly connected machine (the demo Mac mini): its dialog and its dashboard.
                JDialog addSsh = new AddSshServerDialog(frame, service, null);
                write(addSsh, addSsh.getContentPane(), addSsh.getWidth(), addSsh.getContentPane().getPreferredSize().height,
                        "add-ssh-server.png");
                addSsh.dispose();

                JDialog editAccount = new AddAccountDialog(frame, service, true, service.accounts().getFirst());
                write(editAccount, editAccount.getContentPane(), editAccount.getWidth(),
                        editAccount.getContentPane().getPreferredSize().height, "edit-account.png");
                editAccount.dispose();

                var updates = new com.infradesk.service.UpdateService(java.net.http.HttpClient.newHttpClient(),
                        java.net.URI.create("http://127.0.0.1:1"), "x", null);
                JDialog settings = new SettingsDialog(frame, InfraDeskApp.demoAlertService(), false, updates,
                        com.infradesk.app.LaunchAtLogin.forCurrentOs(), () -> { });
                write(settings, settings.getContentPane(), settings.getWidth(),
                        settings.getContentPane().getPreferredSize().height, "settings.png");
                settings.dispose();

                // Windows tray menu (Swing popup): the main list and the first server's submenu side by side.
                TrayController.Actions noop = (TrayController.Actions) java.lang.reflect.Proxy.newProxyInstance(
                        TrayController.Actions.class.getClassLoader(), new Class<?>[]{TrayController.Actions.class}, (o, m, a) -> null);
                var entries = TrayController.entries(inventory, java.util.Map.of(inventory.getFirst().servers().getFirst().id(), 23.0),
                        " · 18:42 갱신", true, null, noop);
                var sub = entries.stream().filter(e -> e instanceof TrayController.Entry.ServerMenu).findFirst()
                        .map(e -> ((TrayController.Entry.ServerMenu) e).items()).orElseThrow();
                // Popups are invisible until shown, so lay them out and paint them by hand.
                javax.swing.JPopupMenu[] menus = {SwingTrayMenu.build(entries), SwingTrayMenu.build(sub)};
                javax.swing.JWindow trayWindow = new javax.swing.JWindow();
                trayWindow.addNotify();
                int trayW = 16;
                int trayH = 0;
                for (var m : menus) {
                    trayWindow.getContentPane().add(m);
                    m.setSize(m.getPreferredSize());
                    layoutAll(m);
                    trayW += m.getWidth() + 16;
                    trayH = Math.max(trayH, m.getHeight() + 32);
                }
                BufferedImage trayImg = new BufferedImage(trayW * 2, trayH * 2, BufferedImage.TYPE_INT_RGB);
                Graphics2D tg = trayImg.createGraphics();
                tg.setColor(Theme.APP_BG);
                tg.fillRect(0, 0, trayImg.getWidth(), trayImg.getHeight());
                tg.scale(2, 2);
                tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int x = 16;
                for (var m : menus) {
                    Graphics2D mg = (Graphics2D) tg.create(x, 16, m.getWidth(), m.getHeight());
                    m.paint(mg);
                    mg.dispose();
                    x += m.getWidth() + 16;
                }
                tg.dispose();
                ImageIO.write(trayImg, "png", new File(DIR, "tray-menu-windows.png"));
                trayWindow.dispose();

                var installer = new com.infradesk.service.UpdateInstaller(java.net.http.HttpClient.newHttpClient(),
                        com.infradesk.service.UpdateInstaller.Platform.MAC, java.nio.file.Path.of("/Applications/InfraDesk.app"),
                        java.nio.file.Path.of("build/tmp"), java.nio.file.Path.of("build/tmp/update.log"), false);
                var release = new com.infradesk.service.UpdateService.Release(8, "abc1234", "https://p", "https://d",
                        "https://u/InfraDesk-beta-macOS.zip", "https://u/SHA256SUMS.txt");
                UpdateProgressDialog progress = new UpdateProgressDialog(frame, installer, release, p -> { });
                progress.previewProgress(31_400_000, 74_900_000);
                write(progress, progress.getContentPane(), progress.getWidth(), progress.getContentPane().getPreferredSize().height,
                        "update-progress.png");
                progress.dispose();

                JDialog ssh = new com.infradesk.ui.terminal.SshSettingsDialog(frame, InfraDeskApp.demoTerminalService(),
                        inventory.getFirst().servers().getFirst());
                write(ssh, ssh.getContentPane(), ssh.getWidth(), ssh.getContentPane().getPreferredSize().height,
                        "ssh-settings.png");
                ssh.dispose();
                empty.dispose();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // Directly connected Mac mini: facts and the first SSH sample arrive asynchronously.
        MainFrame[] sshFrame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            var mac = inventory.stream().filter(i -> i.account().provider() == com.infradesk.core.ProviderType.SSH)
                    .findFirst().orElseThrow().servers().getFirst();
            sshFrame[0] = new MainFrame(service, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
            sshFrame[0].setInventory(inventory);
            sshFrame[0].showServer(mac);
        });
        Thread.sleep(2500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(sshFrame[0], sshFrame[0].getContentPane(), 1280, 800, "main-ssh.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            sshFrame[0].dispose();
        });
        // Live mode: flip the toggle on the main frame and let the demo /proc stream produce samples.
        SwingUtilities.invokeAndWait(() -> {
            MainFrame live = mainFrame[0];
            live.addNotify();
            find(live.getContentPane(), javax.swing.JToggleButton.class).doClick();
        });
        Thread.sleep(9000);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(mainFrame[0], mainFrame[0].getContentPane(), 1280, 800, "main-live.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // Terminal: open a demo session, give the fake shell time to print its banner, then render.
        MainFrame[] terminalFrame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            terminalFrame[0] = new MainFrame(service, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
            terminalFrame[0].setInventory(inventory);
            terminalFrame[0].addNotify();
            terminalFrame[0].getContentPane().setSize(1280, 800);
            layoutAll(terminalFrame[0].getContentPane());
            terminalFrame[0].openTerminalFor(inventory.get(1).servers().getFirst());
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            var widget = find(terminalFrame[0].getContentPane(), com.jediterm.terminal.ui.JediTermWidget.class);
            try {
                widget.getTtyConnector().write("free -h\rdocker ps\r");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(1000);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(terminalFrame[0], terminalFrame[0].getContentPane(), 1280, 800, "terminal.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // MySQL console inside a demo container, opened like the containers panel's "MySQL 콘솔" button.
        SwingUtilities.invokeAndWait(() -> terminalFrame[0].openContainerTerminal(inventory.get(1).servers().getFirst(),
                new com.infradesk.ssh.Container("c".repeat(64), "mysql", "mysql:8.4", "running", "Up 14 days",
                        "3306/tcp", 0.4, "412MiB / 23.4GiB", 1.7),
                com.infradesk.ssh.DockerCommands.Console.MYSQL));
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> widgets = new java.util.ArrayList<>();
            collect(terminalFrame[0].getContentPane(), "JediTermWidget", widgets);
            var widget = (com.jediterm.terminal.ui.JediTermWidget) widgets.getLast();
            try {
                widget.getTtyConnector().write("show databases;\rselect count(*) from app.users;\r");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(1000);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(terminalFrame[0], terminalFrame[0].getContentPane(), 1280, 800, "terminal-mysql.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // SFTP browser on the demo file tree.
        JDialog[] sftp = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> sftp[0] = new com.infradesk.ui.terminal.SftpDialog(terminalFrame[0],
                InfraDeskApp.demoTerminalService(), inventory.getFirst().servers().getFirst()));
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(sftp[0], sftp[0].getContentPane(), sftp[0].getWidth(), sftp[0].getHeight() - 28, "sftp.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // Zoomable metric chart: 24h network, then zoomed in with the hover tooltip.
        var chartServer = inventory.getFirst().servers().getFirst();
        var chartAccount = inventory.getFirst().account();
        var chartDialog = new com.infradesk.ui.metrics.MetricChartDialog(terminalFrame[0], chartServer.name(),
                com.infradesk.ui.metrics.MetricKind.NETWORK, true, range -> service.metrics(chartAccount, chartServer.id(), range));
        SwingUtilities.invokeAndWait(chartDialog::startLoading);
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(chartDialog, chartDialog.getContentPane(), 960, 520, "metric-chart.png");
                var panel = find(chartDialog.getContentPane(), "ZoomChartPanel");
                for (int i = 0; i < 3; i++) {
                    var e = new java.awt.event.MouseWheelEvent(panel, java.awt.event.MouseEvent.MOUSE_WHEEL,
                            System.currentTimeMillis(), 0, 600, 200, 600, 200, 0, false,
                            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -3, -3.0);
                    for (var l : panel.getMouseWheelListeners()) {
                        l.mouseWheelMoved(e);
                    }
                }
                var move = new java.awt.event.MouseEvent(panel, java.awt.event.MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, 500, 200, 0, false);
                for (var l : panel.getMouseMotionListeners()) {
                    l.mouseMoved(move);
                }
                write(chartDialog, chartDialog.getContentPane(), 960, 520, "metric-chart-zoomed.png");
                ((javax.swing.AbstractButton) findText(chartDialog.getContentPane(), "24시간")).doClick();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(chartDialog, chartDialog.getContentPane(), 960, 520, "metric-chart-24h.png");
                ((javax.swing.AbstractButton) findText(chartDialog.getContentPane(), "7일")).doClick();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(chartDialog, chartDialog.getContentPane(), 960, 520, "metric-chart-7d.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("Snapshots written to " + DIR.getAbsolutePath());
        System.exit(0);
    }

    private static java.awt.Component findText(Container root, String text) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.AbstractButton b && text.equals(b.getText())) {
                return c;
            }
            if (c instanceof Container inner) {
                var found = findText(inner, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static java.awt.Component find(Container root, String className) {
        for (java.awt.Component c : root.getComponents()) {
            if (c.getClass().getSimpleName().equals(className)) {
                return c;
            }
            if (c instanceof Container inner) {
                var found = find(inner, className);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void write(Window window, Container pane, int width, int height, String name) throws IOException {
        window.addNotify();
        pane.setSize(width, height);
        layoutAll(pane);
        BufferedImage img = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        pane.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(DIR, name));
    }

    /** Demo terminal whose fake /proc stream ticks every 150 ms, so the live snapshot has a full chart. */
    private static com.infradesk.service.TerminalService fastLiveTerminalService() {
        return new com.infradesk.service.TerminalService(new com.infradesk.storage.InMemorySshSettingsStore(),
                new com.infradesk.storage.InMemorySecretStore(),
                new com.infradesk.ssh.DemoShellConnector(Duration.ZERO, Duration.ofMillis(150)),
                new com.infradesk.storage.InMemorySavedCommandStore(com.infradesk.provider.demo.DemoData.savedCommands()), true);
    }

    private static void collect(Container c, String simpleName, List<java.awt.Component> out) {
        for (var child : c.getComponents()) {
            if (child.getClass().getSimpleName().equals(simpleName)) {
                out.add(child);
            }
            if (child instanceof Container cc) {
                collect(cc, simpleName, out);
            }
        }
    }

    private static <T> T find(Container c, Class<T> type) {
        for (var child : c.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container cc) {
                T found = find(cc, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layoutAll(Container c) {
        c.doLayout();
        for (var child : c.getComponents()) {
            if (child instanceof Container cc) {
                layoutAll(cc);
            }
        }
    }
}
