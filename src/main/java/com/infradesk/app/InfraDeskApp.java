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
import com.infradesk.service.UpdateService;
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
import com.infradesk.storage.VaultSecretStore;
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
        if (Arrays.asList(args).contains("--migrate-secrets")) {
            System.exit(migrateSecrets());
        }
        Logging.install(AppPaths.logDir());
        com.infradesk.ui.ErrorReporter.install();
        boolean demo = Arrays.asList(args).contains("--demo") || Boolean.getBoolean("infradesk.demo");
        java.util.logging.Logger.getLogger(InfraDeskApp.class.getName()).info(() -> "InfraDesk " + BuildInfo.current().display()
                + " starting · Java " + System.getProperty("java.version") + " · " + System.getProperty("os.name")
                + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"
                + (demo ? " · demo" : "") + (Arrays.asList(args).contains("--minimized") ? " · minimized" : ""));
        boolean debugInput = Arrays.asList(args).contains("--debug-input");
        boolean minimized = Arrays.asList(args).contains("--minimized");
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
                frame = new MainFrame(demoService(), demoTerminalService(), demoAlertService(), null, true);
            } else {
                // One keychain item (the vault's master key); every secret lives in the encrypted vault.
                KeychainSecretStore keychain = new KeychainSecretStore();
                SecretStore secrets = new VaultSecretStore(AppPaths.configDir(), keychain, keychain);
                AlertService alerts = new AlertService(new JsonAlertSettingsStore(AppPaths.configDir()), secrets, null,
                        Clock.systemUTC());
                UpdateService updates = new UpdateService(java.net.http.HttpClient.newHttpClient(),
                        UpdateService.BETA_RELEASE_API, UpdateService.assetForCurrentOs(),
                        AppPaths.configDir().resolve("app-settings.json"));
                frame = new MainFrame(realService(secrets), realTerminalService(secrets), alerts, updates, false);
            }
            AppIcon.applyTo(frame);
            // --minimized (login item): stay in the menu bar; without a tray there'd be no way back, so show.
            if (!minimized || !frame.hasTray()) {
                frame.setVisible(true);
            }
            frame.refresh();
            frame.startUpdateChecks();
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

    /**
     * {@code --migrate-secrets}: moves every per-item keychain secret into the vault and deletes the
     * old items. Meant to run once from the JVM that created them (./gradlew run), which reads them
     * without a keychain prompt.
     */
    private static int migrateSecrets() {
        var dir = AppPaths.configDir();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (var a : new JsonAccountStore(dir).load()) {
            names.add(SecretStore.accountKey(a.id(), com.infradesk.core.AccountSecrets.PRIVATE_KEY));
        }
        try {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(dir.resolve("ssh-settings.json").toFile());
            tree.fieldNames().forEachRemaining(id -> {
                names.add("server." + id + "." + TerminalService.SSH_KEY);
                names.add("server." + id + "." + TerminalService.SSH_PASSPHRASE);
            });
        } catch (java.io.IOException ignored) {
            // No SSH settings yet.
        }
        names.add("alerts.discordWebhook");
        KeychainSecretStore keychain = new KeychainSecretStore();
        int copied = new VaultSecretStore(dir, keychain, keychain).migrateFromLegacy(names);
        System.out.println("옮긴 비밀값 " + copied + "개 → " + dir.resolve("secrets.vault")
                + " (확인한 항목 " + names.size() + "개, 옛 키체인 항목은 삭제)");
        return 0;
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
