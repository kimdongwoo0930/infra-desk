package com.infradesk.app;

import com.formdev.flatlaf.util.SystemInfo;
import com.infradesk.alert.Alert;
import com.infradesk.alert.AlertService;
import com.infradesk.alert.AlertSettings;
import com.infradesk.alert.Notifier;
import com.infradesk.core.ProviderType;
import com.infradesk.provider.demo.DemoData;
import com.infradesk.provider.demo.DemoProviderFactory;
import com.infradesk.provider.oracle.OracleProviderFactory;
import com.infradesk.service.InventoryService;
import com.infradesk.service.ProviderRegistry;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.DemoShellConnector;
import com.infradesk.ssh.MinaShellConnector;
import com.infradesk.storage.AppPaths;
import com.infradesk.storage.InMemoryAccountStore;
import com.infradesk.storage.InMemoryAlertSettingsStore;
import com.infradesk.storage.InMemorySavedCommandStore;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.InMemorySshSettingsStore;
import com.infradesk.storage.JsonAccountStore;
import com.infradesk.storage.JsonAlertSettingsStore;
import com.infradesk.storage.JsonSavedCommandStore;
import com.infradesk.storage.JsonSshSettingsStore;
import com.infradesk.storage.KeychainSecretStore;
import com.infradesk.storage.SecretStore;
import com.infradesk.ui.MainFrame;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Toast;

import java.time.Clock;
import java.util.Arrays;
import javax.swing.SwingUtilities;

/**
 * Application entry point. Pass {@code --demo} (or {@code -Dinfradesk.demo=true}) to run with fake
 * data: no settings file or keychain access, no network.
 */
public final class InfraDeskApp {

    private InfraDeskApp() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--self-test")) {
            System.exit(SelfTest.run());
        }
        boolean demo = Arrays.asList(args).contains("--demo") || Boolean.getBoolean("infradesk.demo");
        boolean debugInput = Arrays.asList(args).contains("--debug-input");
        if (SystemInfo.isMacOS) {
            System.setProperty("apple.awt.application.name", "InfraDesk");
            System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            // Menu-bar icon as a template image: monochrome, follows the light/dark menu bar.
            System.setProperty("apple.awt.enableTemplateImages", "true");
        }
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            AppIcon.applyToTaskbar();
            if (debugInput) {
                InputDiagnostics.install();
            }
            MainFrame frame;
            if (demo) {
                frame = new MainFrame(demoService(), demoTerminalService(), demoAlertService(), true);
            } else {
                SecretStore secrets = new KeychainSecretStore();
                AlertService alerts = new AlertService(new JsonAlertSettingsStore(AppPaths.configDir()), secrets, null,
                        Clock.systemUTC());
                frame = new MainFrame(realService(secrets), realTerminalService(secrets), alerts, false);
            }
            AppIcon.applyTo(frame);
            frame.setVisible(true);
            frame.refresh();
        });
    }

    /** Alerts in demo mode never leave the machine: they show as a preview toast instead. */
    public static AlertService demoAlertService() {
        Notifier preview = alert -> SwingUtilities.invokeLater(() -> {
            java.awt.Window w = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                if (w == null && f.isShowing()) {
                    w = f;
                }
            }
            Toast.show(w, "디스코드 알림 미리보기 (데모 · 전송 안 함)", alert.title(), alert.description(),
                    alert.level() == Alert.Level.PROBLEM ? Theme.DANGER_TEXT
                            : alert.level() == Alert.Level.RECOVERED ? Theme.RUNNING_DOT : Theme.ACCENT);
        });
        return new AlertService(new InMemoryAlertSettingsStore(
                new AlertSettings(true, true, true, true, 90, 5)), new InMemorySecretStore(), preview, Clock.systemUTC());
    }

    public static TerminalService demoTerminalService() {
        return new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(), new DemoShellConnector(),
                new InMemorySavedCommandStore(DemoData.savedCommands()), true);
    }

    private static TerminalService realTerminalService(SecretStore secrets) {
        var connector = new MinaShellConnector(AppPaths.configDir().resolve("known_hosts"));
        Runtime.getRuntime().addShutdownHook(new Thread(connector::close));
        return new TerminalService(new JsonSshSettingsStore(AppPaths.configDir()), secrets, connector,
                new JsonSavedCommandStore(AppPaths.configDir()), false);
    }

    public static InventoryService demoService(DemoProviderFactory factory) {
        ProviderRegistry registry = new ProviderRegistry();
        for (ProviderType type : ProviderType.values()) {
            registry.register(type, factory);
        }
        return new InventoryService(new InMemoryAccountStore(DemoData.accounts()), new InMemorySecretStore(), registry);
    }

    private static InventoryService demoService() {
        return demoService(new DemoProviderFactory());
    }

    private static InventoryService realService(SecretStore secrets) {
        ProviderRegistry registry = new ProviderRegistry().register(ProviderType.ORACLE, new OracleProviderFactory());
        return new InventoryService(new JsonAccountStore(AppPaths.configDir()), secrets, registry);
    }
}
