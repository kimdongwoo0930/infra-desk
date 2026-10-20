package com.infradesk.alert;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 인벤토리와 CPU 갱신을 알림으로 바꾼다. 순수한 상태 기계: 호출하는 쪽이 데이터를 넣고
 * 반환된 알림을 전송한다. 문제마다 알림은 한 번만 가고, 짝이 되는 "복구" 알림이 간다.
 */
public class AlertMonitor {

    /** 앱에서 시작한 정지/재부팅이 "서버 다운" 알림을 얼마나 오래 억제하는지. */
    static final Duration EXPECTED_WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final Supplier<AlertSettings> settings;

    private final Map<String, ServerStatus> lastStatus = new HashMap<>();
    private final Map<String, Instant> expectedUntil = new HashMap<>();
    private final Set<String> downAlerted = new HashSet<>();
    private final Set<String> failingAccounts = new HashSet<>();
    private final Map<String, Integer> cpuHighStreak = new HashMap<>();
    private final Set<String> cpuAlerted = new HashSet<>();
    private final Map<String, String> names = new HashMap<>();

    public AlertMonitor(Clock clock, Supplier<AlertSettings> settings) {
        this.clock = clock;
        this.settings = settings;
    }

    /** 사용자가 앱에서 서버를 시작하거나 정지했다. 이후 상태 변화는 예상된 것이다. */
    public synchronized void expectChange(String serverId) {
        expectedUntil.put(serverId, clock.instant().plus(EXPECTED_WINDOW));
    }

    /** 전체 또는 일부 인벤토리 갱신을 넣는다. */
    public synchronized List<Alert> onInventory(List<AccountInventory> inventory) {
        AlertSettings s = settings.get();
        List<Alert> alerts = new ArrayList<>();
        Instant now = clock.instant();
        for (AccountInventory inv : inventory) {
            String accountId = inv.account().id();
            if (inv.failed()) {
                if (failingAccounts.add(accountId) && s.enabled() && s.accountError()) {
                    alerts.add(new Alert(Alert.Level.PROBLEM, "⚠️ 계정 연결 오류: " + inv.account().displayName(),
                            inv.error(), now));
                }
                continue;
            }
            if (failingAccounts.remove(accountId) && s.enabled() && s.accountError()) {
                alerts.add(new Alert(Alert.Level.RECOVERED, "✅ 계정 연결 복구: " + inv.account().displayName(),
                        "서버 목록을 다시 불러왔어요.", now));
            }
            for (Server server : inv.servers()) {
                names.put(server.id(), server.name());
                ServerStatus before = lastStatus.put(server.id(), server.status());
                boolean expected = expectedUntil.getOrDefault(server.id(), Instant.MIN).isAfter(now);
                if (before == ServerStatus.RUNNING && isDown(server.status()) && !expected) {
                    downAlerted.add(server.id());
                    if (s.enabled() && s.serverDown()) {
                        alerts.add(new Alert(Alert.Level.PROBLEM, "🔴 서버가 멈췄어요: " + server.name(),
                                inv.account().displayName() + " · " + server.region() + "\n상태: " + server.status().label()
                                        + " (앱에서 요청하지 않은 변경)", now));
                    }
                } else if (server.status() == ServerStatus.RUNNING && downAlerted.remove(server.id())
                        && s.enabled() && s.serverDown()) {
                    alerts.add(new Alert(Alert.Level.RECOVERED, "🟢 서버가 다시 실행 중이에요: " + server.name(),
                            inv.account().displayName() + " · " + server.region(), now));
                }
                if (server.status() != ServerStatus.RUNNING) {
                    cpuHighStreak.remove(server.id());
                    cpuAlerted.remove(server.id());
                }
            }
        }
        expectedUntil.values().removeIf(t -> t.isBefore(now));
        return alerts;
    }

    /** 서버별 최신 CPU를 넣는다. 1분에 한 번 정도 호출한다. */
    public synchronized List<Alert> onCpu(Map<String, Double> cpu) {
        AlertSettings s = settings.get();
        List<Alert> alerts = new ArrayList<>();
        Instant now = clock.instant();
        for (Map.Entry<String, Double> e : cpu.entrySet()) {
            String id = e.getKey();
            double value = e.getValue();
            String name = names.getOrDefault(id, id);
            if (value >= s.cpuThreshold()) {
                int streak = cpuHighStreak.merge(id, 1, Integer::sum);
                if (streak >= s.cpuMinutes() && cpuAlerted.add(id) && s.enabled() && s.cpuHigh()) {
                    alerts.add(new Alert(Alert.Level.PROBLEM, "🔥 CPU 사용률 높음: " + name,
                            "CPU " + Math.round(value) + "% · " + s.cpuMinutes() + "분 넘게 " + s.cpuThreshold() + "% 이상", now));
                }
            } else {
                cpuHighStreak.remove(id);
                if (cpuAlerted.remove(id) && s.enabled() && s.cpuHigh()) {
                    alerts.add(new Alert(Alert.Level.RECOVERED, "✅ CPU 사용률 정상: " + name,
                            "CPU " + Math.round(value) + "%", now));
                }
            }
        }
        return alerts;
    }

    private static boolean isDown(ServerStatus status) {
        return status == ServerStatus.STOPPING || status == ServerStatus.STOPPED
                || status == ServerStatus.TERMINATING || status == ServerStatus.TERMINATED;
    }
}
