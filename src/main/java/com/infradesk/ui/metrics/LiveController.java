package com.infradesk.ui.metrics;

import java.time.Duration;

/**
 * 실시간(SSH) 모드의 상태. 테스트할 수 있도록 Swing과 분리되어 있다.
 *
 * <pre>
 * OFF ──toggle on──► CONNECTING ──opened──► RUNNING ──(10 min)──► OFF
 *                        │  ▲                  │
 *                  pause │  │ resume     pause │
 *                        ▼  │                  ▼
 *                       PAUSED ◄───────────────┘
 * </pre>
 *
 * 일시 중지는 사용자의 선택을 유지한다: 대시보드가 가려지거나 창이 최소화되어 있는 동안에는
 * 연결을 닫고, 다시 보이면 다시 연다.
 */
public final class LiveController {

    /** 실시간 모드가 스스로 꺼지기 전까지 켜져 있는 시간. */
    public static final Duration AUTO_OFF = Duration.ofMinutes(10);

    public enum State { OFF, CONNECTING, RUNNING, PAUSED }

    /** UI가 구현하는 부수 효과. */
    public interface Hooks {
        /** 연결을 시작한다. {@link #opened()} 또는 {@link #failed(String)}로 결과를 알린다. */
        void open();

        /** 현재 연결이 있으면 닫는다. */
        void close();

        void showToggle(boolean on);

        void showStatus(String text, boolean error);
    }

    /** 한 번만 울리는 타이머. 테스트가 직접 울릴 수 있다. */
    public interface Timeout {
        void start(Duration delay, Runnable onExpire);

        void cancel();
    }

    private final Hooks hooks;
    private final Timeout timeout;
    private State state = State.OFF;

    public LiveController(Hooks hooks, Timeout timeout) {
        this.hooks = hooks;
        this.timeout = timeout;
    }

    public State state() {
        return state;
    }

    /** 사용자가 토글을 켜거나 껐다. */
    public void userToggle(boolean on) {
        if (on && state == State.OFF) {
            connect();
        } else if (!on && state != State.OFF) {
            turnOff(null);
        }
    }

    /** 연결이 되었다. 더 이상 필요하지 않으면 false를 돌려주며, 그러면 호출한 쪽이 연결을 닫는다. */
    public boolean opened() {
        if (state != State.CONNECTING) {
            return false;
        }
        state = State.RUNNING;
        timeout.start(AUTO_OFF, () -> turnOff("10분이 지나 실시간을 껐어요. 다시 켜면 이어서 볼 수 있어요"));
        return true;
    }

    /** 연결에 실패했거나 스트림이 스스로 끝났다. */
    public void failed(String reason) {
        if (state == State.CONNECTING || state == State.RUNNING) {
            turnOff(reason);
        }
    }

    /** 대시보드가 가려졌거나 창이 최소화되었다. */
    public void pause() {
        if (state == State.CONNECTING || state == State.RUNNING) {
            timeout.cancel();
            hooks.close();
            state = State.PAUSED;
            hooks.showStatus("실시간 일시정지 (화면이 보이면 다시 연결해요)", false);
        }
    }

    /** 대시보드가 다시 보인다. */
    public void resume() {
        if (state == State.PAUSED) {
            connect();
        }
    }

    /** 조용히 끈다. 예: 다른 서버를 선택했거나 이 서버가 정지했을 때. */
    public void stop() {
        if (state != State.OFF) {
            turnOff(null);
        }
    }

    private void connect() {
        state = State.CONNECTING;
        hooks.showToggle(true);
        hooks.showStatus("SSH 연결 중…", false);
        hooks.open();
    }

    private void turnOff(String reason) {
        timeout.cancel();
        hooks.close();
        state = State.OFF;
        hooks.showToggle(false);
        if (reason != null) {
            hooks.showStatus(reason, true);
        }
    }
}
