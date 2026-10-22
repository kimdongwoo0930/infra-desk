package com.infradesk.service;

import com.infradesk.ssh.SshException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 사용자가 시키지 않았는데 앱이 스스로 SSH에 접속하는 작업(직접 연결 서버의 분당 측정)이 서버에
 * 부담을 주지 않게 막는다. SSH 접속은 서버의 인증 로그에 남고, 인증이 계속 실패하면 서버의 보안 도구
 * (fail2ban 등)가 이쪽 IP를 차단할 수 있어서 규칙이 필요하다.
 *
 * <ul>
 *   <li><b>설정 문제로 실패하면 멈춘다.</b> 호스트 키를 믿지 않았거나(처음 접속, 바뀜), 키를 읽을 수 없거나,
 *       인증이 두 번 연속 실패하면 사용자가 고치기 전까지 자동 접속을 하지 않는다. 사용자가 설정을 저장하거나
 *       새로고침하면({@link #reset}) 다시 시도한다.</li>
 *   <li><b>그 밖의 실패(연결 끊김, 시간 초과)는 점점 길게 기다린다.</b> 2분, 5분, 이후 15분.</li>
 *   <li><b>창이 숨어 있으면 {@value #HIDDEN_MINUTES}분에 한 번만</b> 접속한다.</li>
 *   <li><b>어떤 경우에도 {@value #MIN_GAP_SECONDS}초 안에 두 번 접속하지 않는다</b>(여러 곳에서 동시에 요청해도).</li>
 * </ul>
 */
final class SamplingGate {

    static final int MIN_GAP_SECONDS = 15;
    static final int HIDDEN_MINUTES = 5;
    private static final Duration MIN_GAP = Duration.ofSeconds(MIN_GAP_SECONDS);
    private static final Duration HIDDEN_INTERVAL = Duration.ofMinutes(HIDDEN_MINUTES);
    private static final Duration[] BACKOFF = {Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(15)};
    /** 인증이 이만큼 연속으로 실패하면 멈춘다. */
    private static final int AUTH_FAILURES_BEFORE_STOP = 2;

    private static final class State {
        Instant lastAttempt;
        Instant nextAllowed;
        int failures;
        int authFailures;
        SshException.Kind blockedBy;
    }

    private final Clock clock;
    private final Map<String, State> states = new HashMap<>();

    SamplingGate(Clock clock) {
        this.clock = clock;
    }

    /**
     * 지금 접속해도 되는지 묻고, 된다면 접속한 것으로 기록한다(동시에 두 곳에서 물어도 한쪽만 통과한다).
     *
     * @param windowVisible 앱 창이 보이는 상태인지. 아니면 접속 간격을 길게 잡는다
     */
    synchronized boolean tryAcquire(String serverId, boolean windowVisible) {
        State s = states.computeIfAbsent(serverId, id -> new State());
        if (s.blockedBy != null) {
            return false;
        }
        Instant now = clock.instant();
        if (s.lastAttempt != null) {
            Duration gap = Duration.between(s.lastAttempt, now);
            if (gap.compareTo(MIN_GAP) < 0 || (!windowVisible && gap.compareTo(HIDDEN_INTERVAL) < 0)) {
                return false;
            }
        }
        if (s.nextAllowed != null && now.isBefore(s.nextAllowed)) {
            return false;
        }
        s.lastAttempt = now;
        return true;
    }

    /** 접속해서 측정까지 성공했다. 실패 기록을 모두 지운다. */
    synchronized void success(String serverId) {
        State s = states.get(serverId);
        if (s != null) {
            s.failures = 0;
            s.authFailures = 0;
            s.nextAllowed = null;
            s.blockedBy = null;
        }
    }

    /**
     * 접속하거나 측정하지 못했다.
     *
     * @param kind 연결 단계에서 난 실패의 종류. 연결은 됐는데 결과를 못 읽었거나 시간이 초과된 경우는 null
     * @return 이 실패로 자동 접속을 멈추게 됐으면 true(처음 멈출 때 한 번만 알리려고)
     */
    synchronized boolean failure(String serverId, SshException.Kind kind) {
        State s = states.computeIfAbsent(serverId, id -> new State());
        Instant now = clock.instant();
        if (kind == SshException.Kind.HOST_KEY_CHANGED || kind == SshException.Kind.HOST_KEY_REJECTED
                || kind == SshException.Kind.KEY_FORMAT) {
            // 사용자가 직접 고치기 전에는 몇 번을 시도해도 성공할 수 없다.
            boolean first = s.blockedBy == null;
            s.blockedBy = kind;
            return first;
        }
        if (kind == SshException.Kind.AUTH) {
            s.authFailures++;
            if (s.authFailures >= AUTH_FAILURES_BEFORE_STOP) {
                boolean first = s.blockedBy == null;
                s.blockedBy = kind;
                return first;
            }
            s.nextAllowed = now.plus(BACKOFF[0]);
            return false;
        }
        s.failures++;
        s.nextAllowed = now.plus(BACKOFF[Math.min(s.failures, BACKOFF.length) - 1]);
        return false;
    }

    /** 자동 접속을 멈추게 한 이유. 멈추지 않았으면 비어 있다. */
    synchronized Optional<SshException.Kind> blocked(String serverId) {
        State s = states.get(serverId);
        return s == null ? Optional.empty() : Optional.ofNullable(s.blockedBy);
    }

    /**
     * 사용자가 설정을 고쳤거나 직접 새로고침했다. 멈춤과 기다림을 풀어서 다시 시도하게 한다. 직전
     * 접속 시각은 남겨 두므로, 새로고침을 연타해도 {@value #MIN_GAP_SECONDS}초 간격은 지킨다.
     */
    synchronized void reset(String serverId) {
        State s = states.get(serverId);
        if (s != null) {
            s.failures = 0;
            s.authFailures = 0;
            s.nextAllowed = null;
            s.blockedBy = null;
        }
    }

    synchronized void resetAll() {
        for (String id : states.keySet()) {
            reset(id);
        }
    }

    synchronized void forget(String serverId) {
        states.remove(serverId);
    }
}
