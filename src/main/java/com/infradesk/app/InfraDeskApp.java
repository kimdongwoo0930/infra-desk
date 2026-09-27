package com.infradesk.app;

import com.formdev.flatlaf.util.SystemInfo;
import com.infradesk.core.ProviderType;
import com.infradesk.provider.demo.DemoData;
import com.infradesk.provider.demo.DemoProviderFactory;
import com.infradesk.provider.oracle.OracleProviderFactory;
import com.infradesk.service.InventoryService;
import com.infradesk.service.ProviderRegistry;
import com.infradesk.storage.AppPaths;
import com.infradesk.storage.InMemoryAccountStore;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.JsonAccountStore;
import com.infradesk.storage.KeychainSecretStore;
import com.infradesk.ui.MainFrame;
import com.infradesk.ui.Theme;

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
        boolean demo = Arrays.asList(args).contains("--demo") || Boolean.getBoolean("infradesk.demo");
        if (SystemInfo.isMacOS) {
            System.setProperty("apple.awt.application.name", "InfraDesk");
            System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
        }
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            MainFrame frame = new MainFrame(demo ? demoService() : realService(), demo);
            frame.setVisible(true);
            frame.refresh();
        });
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

    private static InventoryService realService() {
        ProviderRegistry registry = new ProviderRegistry().register(ProviderType.ORACLE, new OracleProviderFactory());
        return new InventoryService(new JsonAccountStore(AppPaths.configDir()), new KeychainSecretStore(), registry);
    }
}
