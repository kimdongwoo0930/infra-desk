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
 * 애플리케이션 진입점. {@code --demo}(또는 {@code -Dinfradesk.demo=true})를 주면 가짜 데이터로
 * 실행한다. 설정 파일과 키체인에 접근하지 않고 네트워크도 쓰지 않는다.
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
            // 템플릿 이미지로 쓰는 메뉴 막대 아이콘: 단색이며 밝은/어두운 메뉴 막대를 따라간다.
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
                // 키체인 항목은 하나(금고의 마스터 키)뿐이고, 모든 비밀값은 암호화된 금고에 들어 있다.
                KeychainSecretStore keychain = new KeychainSecretStore();
                SecretStore secrets = new VaultSecretStore(AppPaths.configDir(), keychain, keychain);
                AlertService alerts = new AlertService(new JsonAlertSettingsStore(AppPaths.configDir()), secrets, null,
                        Clock.systemUTC());
                UpdateService updates = new UpdateService(java.net.http.HttpClient.newHttpClient(),
                        UpdateService.BETA_RELEASE_API, UpdateService.assetForCurrentOs(),
                        AppPaths.configDir().resolve("app-settings.json"));
                frame = new MainFrame(realService(secrets), realTerminalService(secrets), alerts, updates, false);
                com.infradesk.service.UpdateInstaller.forRunningApp(AppPaths.cacheDir().resolve("update"),
                        AppPaths.logDir().resolve("update.log")).ifPresent(frame::setUpdateInstaller);
            }
            AppIcon.applyTo(frame);
            // --minimized(로그인 시 실행): 메뉴 막대에만 머문다. 트레이가 없으면 돌아올 방법이 없으므로 창을 보여준다.
            if (!minimized || !frame.hasTray()) {
                frame.setVisible(true);
            }
            frame.refresh();
            frame.startUpdateChecks();
        });
    }

    /** 데모 모드의 알림은 기기 밖으로 나가지 않고 미리보기 토스트로만 표시된다. */
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
     * {@code --migrate-secrets}: 항목별 키체인에 있던 모든 비밀값을 금고로 옮기고 이전 항목을 삭제한다.
     * 항목을 만든 JVM(./gradlew run)에서 한 번 실행하는 용도이며, 그 JVM은 키체인 승인 창 없이 읽을 수 있다.
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
            // 아직 SSH 설정이 없다.
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
        // 직접 연결 데모 기기는 주소에 따로 지정하지 않으면 응답한다.
        registry.register(ProviderType.SSH, new com.infradesk.provider.ssh.SshHostProviderFactory((host, port) -> !host.startsWith("offline")));
        return new InventoryService(new InMemoryAccountStore(DemoData.accounts()), new InMemorySecretStore(), registry);
    }

    private static InventoryService demoService() {
        return demoService(new DemoProviderFactory());
    }

    private static InventoryService realService(SecretStore secrets) {
        ProviderRegistry registry = new ProviderRegistry()
                .register(ProviderType.ORACLE, new OracleProviderFactory())
                .register(ProviderType.SSH, new com.infradesk.provider.ssh.SshHostProviderFactory());
        return new InventoryService(new JsonAccountStore(AppPaths.configDir()), secrets, registry);
    }
}
