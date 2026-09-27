package com.infradesk.ui.metrics;

import java.time.Duration;

/**
 * State of the live (SSH) mode, separate from Swing so it can be tested.
 *
 * <pre>
 * OFF ──toggle on──► CONNECTING ──opened──► RUNNING ──(10 min)──► OFF
 *                        │  ▲                  │
 *                  pause │  │ resume     pause │
 *                        ▼  │                  ▼
 *                       PAUSED ◄───────────────┘
 * </pre>
 *
 * Paused keeps the user's choice: the connection is closed while the dashboard is hidden or the
 * window is minimized, and reopened when it's visible again.
 */
public final class LiveController {

    /** How long live mode stays on before turning itself off. */
    public static final Duration AUTO_OFF = Duration.ofMinutes(10);

    public enum State { OFF, CONNECTING, RUNNING, PAUSED }

    /** Side effects, implemented by the UI. */
    public interface Hooks {
        /** Start connecting; report back with {@link #opened()} or {@link #failed(String)}. */
        void open();

        /** Close the current connection, if any. */
        void close();

        void showToggle(boolean on);

        void showStatus(String text, boolean error);
    }

    /** One-shot timer, so tests can fire it by hand. */
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

    /** The user flipped the toggle. */
    public void userToggle(boolean on) {
        if (on && state == State.OFF) {
            connect();
        } else if (!on && state != State.OFF) {
            turnOff(null);
        }
    }

    /** The connection is up. Returns false if it's no longer wanted; the caller then closes it. */
    public boolean opened() {
        if (state != State.CONNECTING) {
            return false;
        }
        state = State.RUNNING;
        timeout.start(AUTO_OFF, () -> turnOff("10분이 지나 실시간을 껐어요. 다시 켜면 이어서 볼 수 있어요"));
        return true;
    }

    /** Connecting failed or the stream ended by itself. */
    public void failed(String reason) {
        if (state == State.CONNECTING || state == State.RUNNING) {
            turnOff(reason);
        }
    }

    /** The dashboard was hidden or the window minimized. */
    public void pause() {
        if (state == State.CONNECTING || state == State.RUNNING) {
            timeout.cancel();
            hooks.close();
            state = State.PAUSED;
            hooks.showStatus("실시간 일시정지 (화면이 보이면 다시 연결해요)", false);
        }
    }

    /** The dashboard is visible again. */
    public void resume() {
        if (state == State.PAUSED) {
            connect();
        }
    }

    /** Turns off quietly, e.g. when another server is selected or this one stopped. */
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
