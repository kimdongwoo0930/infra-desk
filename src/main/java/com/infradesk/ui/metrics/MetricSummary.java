package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics.Sample;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * "이 기간 요약" 카드의 계산 (디자인 v2): 지금 화면에 그려진 점들로 평균, 최대, 최대였던 시각을 구하고 글자로 만든다.
 * 서버에 새로 묻지 않고 이미 받은 데이터만 쓴다.
 */
final class MetricSummary {

    private MetricSummary() {
    }

    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault());
    /** 이 길이 이상을 보여 주는 기간(24시간, 7일)은 시각 앞에 날짜를 붙인다. */
    private static final long LONG_SPAN_MS = 20 * 3_600_000L;

    /** 평균, 최대, 최대였던 시각. */
    record Stat(double average, double max, Instant maxAt) {
    }

    /** 점들의 통계. 점이 없으면 비어 있다. 최대가 여러 번이면 가장 최근 시각을 쓴다. */
    static Optional<Stat> stat(List<Sample> samples) {
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        double sum = 0;
        Sample best = null;
        for (Sample s : samples) {
            sum += s.value();
            if (best == null || s.value() >= best.value()) {
                best = s;
            }
        }
        return Optional.of(new Stat(sum / samples.size(), best.value(), best.time()));
    }

    /** 같은 시각의 두 값을 더한다(수신 + 송신). 한쪽에만 있는 시각은 그 값만 쓴다. 시각 순서로 돌려준다. */
    static List<Sample> sumByTime(List<Sample> a, List<Sample> b) {
        TreeMap<Instant, Double> sums = new TreeMap<>();
        for (Sample s : a) {
            sums.merge(s.time(), s.value(), Double::sum);
        }
        for (Sample s : b) {
            sums.merge(s.time(), s.value(), Double::sum);
        }
        return sums.entrySet().stream().map(e -> new Sample(e.getKey(), e.getValue())).toList();
    }

    /** 시각을 어떻게 적을지: 실시간은 초까지, 24시간 이상을 보여 주는 기간은 날짜까지, 그 밖에는 시:분. */
    static DateTimeFormatter timeFormat(List<Sample> samples, boolean live) {
        if (live) {
            return SECOND;
        }
        if (samples.size() >= 2) {
            long span = samples.getLast().time().toEpochMilli() - samples.getFirst().time().toEpochMilli();
            if (span >= LONG_SPAN_MS) {
                return DAY_TIME;
            }
        }
        return MINUTE;
    }

    /** 사용률(%) 한 줄: {@code 평균 24% · 최대 33% (14:31)}. 점이 없으면 {@code —}. */
    static String percentLine(List<Sample> samples, boolean live) {
        return stat(samples).map(s -> "평균 " + Formats.percent(s.average()) + " · 최대 " + Formats.percent(s.max())
                + " (" + timeFormat(samples, live).format(s.maxAt()) + ")").orElse("—");
    }

    /** 네트워크 한 줄: 수신+송신 합의 최대, {@code 최대 55.9 KB/s (15:07)}. 점이 없으면 {@code —}. */
    static String rateLine(List<Sample> receive, List<Sample> send, boolean live) {
        List<Sample> total = sumByTime(receive, send);
        return stat(total).map(s -> "최대 " + Formats.rate(s.max()) + " (" + timeFormat(total, live).format(s.maxAt()) + ")")
                .orElse("—");
    }
}
