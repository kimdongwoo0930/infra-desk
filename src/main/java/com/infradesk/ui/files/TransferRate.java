package com.infradesk.ui.files;

import java.util.HashMap;
import java.util.Map;

/**
 * 전송 속도와 남은 시간을 어림한다. 전송 대기열은 받은 바이트 수만 알려 주므로, 화면이 진행 상황을 받을
 * 때마다 이전 값과 비교해 속도를 구한다. 값이 들쭉날쭉하지 않도록 최근 값에 가중을 둔 평균을 쓴다.
 */
final class TransferRate {

    /** 이보다 짧은 간격의 측정은 건너뛴다(너무 짧으면 속도가 튄다). */
    private static final double MIN_INTERVAL_SECONDS = 0.5;
    /** 새 측정값이 평균에 반영되는 비율. */
    private static final double WEIGHT = 0.3;

    private record Sample(long bytes, long nanos, double speed) {
    }

    private final Map<Long, Sample> samples = new HashMap<>();

    /**
     * 진행 상황을 알린다.
     *
     * @return 지금까지 어림한 속도(바이트/초). 아직 모르면 0
     */
    double update(long jobId, long doneBytes, long nowNanos) {
        Sample previous = samples.get(jobId);
        if (previous == null || doneBytes < previous.bytes) {
            // 처음이거나 이어받기로 처음부터 다시 셈한다.
            samples.put(jobId, new Sample(doneBytes, nowNanos, 0));
            return 0;
        }
        double seconds = (nowNanos - previous.nanos) / 1e9;
        if (seconds < MIN_INTERVAL_SECONDS) {
            return previous.speed;
        }
        double instant = (doneBytes - previous.bytes) / seconds;
        double speed = previous.speed == 0 ? instant : (1 - WEIGHT) * previous.speed + WEIGHT * instant;
        samples.put(jobId, new Sample(doneBytes, nowNanos, speed));
        return speed;
    }

    /** 이 작업의 기록을 잊는다(끝났거나 목록에서 지웠을 때). */
    void forget(long jobId) {
        samples.remove(jobId);
    }

    /** 남은 바이트를 이 속도로 받으면 걸리는 시간 글자. 속도를 모르면 빈 글자. */
    static String remaining(long leftBytes, double speed) {
        if (speed <= 0 || leftBytes < 0) {
            return "";
        }
        long seconds = Math.max(1, Math.round(leftBytes / speed));
        if (seconds < 60) {
            return seconds + "초 남음";
        }
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) {
            return minutes + "분 남음";
        }
        return minutes / 60 + "시간 " + minutes % 60 + "분 남음";
    }
}
