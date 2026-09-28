package com.infradesk.alert;

import com.infradesk.service.AccountInventory;
import com.infradesk.storage.AlertSettingsStore;
import com.infradesk.storage.SecretStore;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Alert settings, the webhook secret, and delivery. Detection is in {@link AlertMonitor}; sending
 * happens on one background thread so alerts go out in order and never block the UI.
 */
public class AlertService {

    /**
     * Discord alerts are shown as "준비 중" and not fed by the app until they have been tried on a
     * real channel. The code and its tests stay; flip this to ship the feature.
     */
    public static final boolean AVAILABLE = false;

    private static final Logger LOG = Logger.getLogger(AlertService.class.getName());
    static final String WEBHOOK_KEY = "alerts.discordWebhook";

    private final AlertSettingsStore settingsStore;
    private final SecretStore secretStore;
    private final Notifier notifier;
    private final AlertMonitor monitor;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alert-sender");
        t.setDaemon(true);
        return t;
    });
    private volatile AlertSettings settings;
    private volatile Consumer<String> onDeliveryFailure = msg -> { };

    /**
     * @param notifier null to use Discord with the stored webhook URL
     */
    public AlertService(AlertSettingsStore settingsStore, SecretStore secretStore, Notifier notifier, Clock clock) {
        this.settingsStore = settingsStore;
        this.secretStore = secretStore;
        this.notifier = notifier != null ? notifier
                : new DiscordNotifier(() -> secretStore.get(WEBHOOK_KEY).orElse(null));
        this.settings = settingsStore.load();
        this.monitor = new AlertMonitor(clock, () -> this.settings);
    }

    public AlertSettings settings() {
        return settings;
    }

    public boolean hasWebhook() {
        return secretStore.get(WEBHOOK_KEY).isPresent();
    }

    /** @param webhookUrl new URL, null to keep the stored one, empty to remove it */
    public void save(AlertSettings newSettings, String webhookUrl) {
        if (webhookUrl != null) {
            if (webhookUrl.isBlank()) {
                secretStore.delete(WEBHOOK_KEY);
            } else {
                secretStore.put(WEBHOOK_KEY, webhookUrl.strip());
            }
        }
        settingsStore.save(newSettings);
        this.settings = newSettings;
    }

    /** Called with a user-facing message when an alert can't be delivered. */
    public void onDeliveryFailure(Consumer<String> listener) {
        this.onDeliveryFailure = listener;
    }

    public void expectChange(String serverId) {
        monitor.expectChange(serverId);
    }

    public void onInventory(List<AccountInventory> inventory) {
        dispatch(monitor.onInventory(inventory));
    }

    public void onCpu(Map<String, Double> cpu) {
        dispatch(monitor.onCpu(cpu));
    }

    /** Sends a test message now, blocking. Throws {@link AlertException} on failure. */
    public void sendTest() {
        notifier.send(new Alert(Alert.Level.INFO, "🔔 InfraDesk 테스트 알림",
                "알림이 이 채널로 와요. 설정은 InfraDesk ⚙ 설정에서 바꿀 수 있어요.", java.time.Instant.now()));
    }

    private void dispatch(List<Alert> alerts) {
        for (Alert a : alerts) {
            sender.submit(() -> {
                try {
                    notifier.send(a);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Alert delivery failed: " + e.getMessage());
                    onDeliveryFailure.accept(e.getMessage());
                }
            });
        }
    }
}
