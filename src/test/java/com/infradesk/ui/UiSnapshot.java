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
        // Stop discord-bot so the "stopping" state can be rendered too.
        var accountA = service.accounts().getFirst();
        service.control(accountA, inventory.getFirst().servers().getFirst().id(), com.infradesk.service.ServerAction.STOP);
        List<AccountInventory> stopping = service.loadAll();

        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            try {
                MainFrame empty = new MainFrame(service, InfraDeskApp.demoTerminalService(), true);
                empty.setInventory(List.of());
                write(empty, empty.getContentPane(), 1280, 800, "main-empty.png");

                MainFrame frame = new MainFrame(service, InfraDeskApp.demoTerminalService(), true);
                frame.setInventory(inventory);
                write(frame, frame.getContentPane(), 1280, 800, "main.png");

                MainFrame stoppingFrame = new MainFrame(service, InfraDeskApp.demoTerminalService(), true);
                stoppingFrame.setInventory(stopping);
                write(stoppingFrame, stoppingFrame.getContentPane(), 1280, 800, "main-stopping.png");
                stoppingFrame.dispose();

                JDialog dialog = new AddAccountDialog(frame, service, false);
                write(dialog, dialog.getContentPane(), dialog.getWidth(), dialog.getContentPane().getPreferredSize().height,
                        "add-account.png");
                dialog.dispose();

                JDialog ssh = new com.infradesk.ui.terminal.SshSettingsDialog(frame, InfraDeskApp.demoTerminalService(),
                        inventory.getFirst().servers().getFirst());
                write(ssh, ssh.getContentPane(), ssh.getWidth(), ssh.getContentPane().getPreferredSize().height,
                        "ssh-settings.png");
                ssh.dispose();
                frame.dispose();
                empty.dispose();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // Terminal: open a demo session, give the fake shell time to print its banner, then render.
        MainFrame[] terminalFrame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            terminalFrame[0] = new MainFrame(service, InfraDeskApp.demoTerminalService(), true);
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
        System.out.println("Snapshots written to " + DIR.getAbsolutePath());
        System.exit(0);
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
