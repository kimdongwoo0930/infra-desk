package com.infradesk.service;

import com.infradesk.ssh.ProcStats;
import com.infradesk.ssh.ShellSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 백그라운드 스레드에서 세션의 {@link ProcStats#COMMAND} 출력을 읽고 간격마다
 * {@link ProcStats.Sample} 하나를 알린다. 첫 블록은 카운터를 준비하는 용도로만 쓴다.
 */
public final class LiveStats implements AutoCloseable {

    private final ShellSession session;
    private volatile boolean running = true;

    private LiveStats(ShellSession session) {
        this.session = session;
    }

    /**
     * @param onSample 샘플마다 리더 스레드에서 호출된다
     * @param onEnd    스트림이 끝나면 리더 스레드에서 한 번 호출된다. 우리가 닫았으면 null
     */
    public static LiveStats start(ShellSession session, Clock clock, Consumer<ProcStats.Sample> onSample,
                                  Consumer<String> onEnd) {
        LiveStats stats = new LiveStats(session);
        Thread.ofVirtual().name("live-stats").start(() -> stats.read(clock, onSample, onEnd));
        return stats;
    }

    private void read(Clock clock, Consumer<ProcStats.Sample> onSample, Consumer<String> onEnd) {
        ProcStats.Snapshot previous = null;
        List<String> block = new ArrayList<>();
        String endReason = "실시간 연결이 끊겼어요";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(session.output(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.equals("---")) {
                    block.add(line);
                    continue;
                }
                var snapshot = ProcStats.parse(block, clock.instant());
                block.clear();
                if (snapshot.isEmpty()) {
                    endReason = "서버의 /proc 형식을 읽지 못했어요 (Linux 서버에서만 지원)";
                    break;
                }
                if (previous != null) {
                    onSample.accept(ProcStats.between(previous, snapshot.get()));
                }
                previous = snapshot.get();
            }
        } catch (IOException | RuntimeException e) {
            endReason = "실시간 데이터를 읽지 못했어요: " + e.getMessage();
        }
        session.close();
        if (running) {
            running = false;
            onEnd.accept(endReason);
        }
    }

    @Override
    public void close() {
        running = false;
        Thread.ofVirtual().start(session::close);
    }
}
