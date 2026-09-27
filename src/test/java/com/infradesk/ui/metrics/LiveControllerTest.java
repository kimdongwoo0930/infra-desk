package com.infradesk.ui.metrics;

import com.infradesk.ui.metrics.LiveController.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveControllerTest {

    private final List<String> calls = new ArrayList<>();
    private Runnable pendingExpire;
    private Duration pendingDelay;
    private LiveController live;

    @BeforeEach
    void setUp() {
        live = new LiveController(new LiveController.Hooks() {
            @Override
            public void open() {
                calls.add("open");
            }

            @Override
            public void close() {
                calls.add("close");
            }

            @Override
            public void showToggle(boolean on) {
                calls.add("toggle " + on);
            }

            @Override
            public void showStatus(String text, boolean error) {
                calls.add((error ? "error " : "status ") + text);
            }
        }, new LiveController.Timeout() {
            @Override
            public void start(Duration delay, Runnable onExpire) {
                pendingDelay = delay;
                pendingExpire = onExpire;
            }

            @Override
            public void cancel() {
                pendingExpire = null;
            }
        });
    }

    private void connectAndOpen() {
        live.userToggle(true);
        assertTrue(live.opened());
        calls.clear();
    }

    @Test
    void togglingOnConnectsAndStartsTenMinuteTimer() {
        live.userToggle(true);
        assertEquals(State.CONNECTING, live.state());
        assertTrue(calls.contains("open"));
        assertTrue(live.opened());
        assertEquals(State.RUNNING, live.state());
        assertEquals(Duration.ofMinutes(10), pendingDelay);
    }

    @Test
    void autoOffAfterTimeout() {
        connectAndOpen();
        pendingExpire.run();
        assertEquals(State.OFF, live.state());
        assertTrue(calls.contains("close"));
        assertTrue(calls.contains("toggle false"));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("error 10분")));
    }

    @Test
    void pauseClosesButKeepsChoiceAndResumeReconnects() {
        connectAndOpen();
        live.pause();
        assertEquals(State.PAUSED, live.state());
        assertTrue(calls.contains("close"));
        assertFalse(calls.contains("toggle false"), "toggle stays on while paused");
        assertNull(pendingExpire, "timer stops while paused");

        calls.clear();
        live.resume();
        assertEquals(State.CONNECTING, live.state());
        assertTrue(calls.contains("open"));
    }

    @Test
    void connectionArrivingAfterPauseIsRejected() {
        live.userToggle(true);
        live.pause();
        assertFalse(live.opened(), "caller must close the late session");
        assertEquals(State.PAUSED, live.state());
    }

    @Test
    void resumeDoesNothingUnlessPaused() {
        live.resume();
        assertEquals(State.OFF, live.state());
        assertTrue(calls.isEmpty());
    }

    @Test
    void failureTurnsOffWithReason() {
        live.userToggle(true);
        live.failed("SSH 인증에 실패했어요");
        assertEquals(State.OFF, live.state());
        assertTrue(calls.contains("error SSH 인증에 실패했어요"));
    }

    @Test
    void failureWhilePausedIsIgnored() {
        connectAndOpen();
        live.pause();
        live.failed("late stream end");
        assertEquals(State.PAUSED, live.state());
    }

    @Test
    void stopIsQuiet() {
        connectAndOpen();
        live.stop();
        assertEquals(State.OFF, live.state());
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("error")));
    }
}
