package com.infradesk.core;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UI와 서비스 코드가 클라우드와 대화할 때 쓰는 유일한 진입점. 인스턴스 하나가 {@link Account}
 * 하나를 담당한다. 모든 메서드는 네트워크 I/O에서 블로킹되므로 EDT에서 절대 호출하지 않는다.
 */
public interface CloudProvider extends AutoCloseable {

    /** 계정의 서버를 조회한다. */
    List<Server> listServers();

    void start(String serverId);

    void stop(String serverId);

    void reboot(String serverId);

    /** 1분 해상도의 최근 사용률을 돌려준다. 대략 최근 1시간. */
    Metrics getMetrics(String serverId);

    /**
     * 최근 {@code range} 동안의 사용률. 더 긴 기록을 보관하는 provider는 이것을 재정의해서
     * 기간에 맞는 해상도를 고른다. 기본 구현은 기간을 무시한다.
     */
    default Metrics getMetrics(String serverId, Duration range) {
        return getMetrics(serverId);
    }

    /**
     * 사이드바용으로 서버 id별 최신 CPU 사용률(0–100). 데이터가 없는 서버는 없다.
     * 기본 구현은 서버마다 {@link #getMetrics}를 호출한다. 모든 서버를 한 번의 호출로 가져올 수 있는
     * provider는 이것을 재정의해야 한다.
     */
    default Map<String, Double> currentCpu(List<Server> servers) {
        Map<String, Double> result = new HashMap<>();
        for (Server s : servers) {
            if (s.status() == ServerStatus.RUNNING) {
                getMetrics(s.id()).latestCpu().ifPresent(v -> result.put(s.id(), v));
            }
        }
        return result;
    }

    /** SDK 클라이언트를 해제한다. */
    @Override
    default void close() {
    }
}
