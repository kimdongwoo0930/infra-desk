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
 * 알림 설정, 웹훅 비밀값, 전송. 감지는 {@link AlertMonitor}가 하고, 전송은 백그라운드 스레드
 * 하나에서 해서 알림이 순서대로 나가고 UI를 막지 않는다.
 */
public class AlertService {

    /**
     * 디스코드 알림은 "준비 중"으로 표시하고, 실제 채널에서 시험해보기 전까지는 앱이 알림을
     * 넣어주지 않는다. 코드와 테스트는 남겨두며, 이 값을 바꾸면 기능이 켜진다.
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
     * @param notifier null이면 저장된 웹훅 URL로 디스코드를 쓴다
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

    /** @param webhookUrl 새 URL. null이면 저장된 값 유지, 빈 값이면 삭제 */
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

    /** 알림을 전달하지 못했을 때 사용자에게 보여줄 메시지와 함께 호출된다. */
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

    /** 테스트 메시지를 지금 보낸다(블로킹). 실패하면 {@link AlertException}을 던진다. */
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
