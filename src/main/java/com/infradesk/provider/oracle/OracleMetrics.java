package com.infradesk.provider.oracle;

import com.infradesk.core.Metrics;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Monitoring({@code oci_computeagent} 네임스페이스, Compute Instance Monitoring 플러그인이
 * 보고)에서 인스턴스 메트릭을 읽는다. OCI는 1분 해상도로 집계한다.
 */
final class OracleMetrics {

    static final String NAMESPACE = "oci_computeagent";
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration CPU_WINDOW = Duration.ofMinutes(5);

    private final MonitoringClient client;
    private final String compartmentId;
    private final Clock clock;

    OracleMetrics(MonitoringClient client, String compartmentId, Clock clock) {
        this.client = client;
        this.compartmentId = compartmentId;
        this.clock = clock;
    }

    /** 인스턴스 하나의 최근 1시간 CPU, 메모리, 네트워크. 쿼리 4개. */
    Metrics forInstance(String instanceId) {
        return forInstance(instanceId, WINDOW);
    }

    /** {@code range} 동안의 CPU, 메모리, 네트워크를 {@link #intervalFor(Duration)} 간격으로. 쿼리 4개. */
    Metrics forInstance(String instanceId, Duration range) {
        String interval = intervalFor(range);
        return new Metrics(
                series(query("CpuUtilization", instanceId, "mean", range, interval)),
                series(query("MemoryUtilization", instanceId, "mean", range, interval)),
                series(query("NetworksBytesIn", instanceId, "rate", range, interval)),
                series(query("NetworksBytesOut", instanceId, "rate", range, interval)));
    }

    /** 긴 기간일수록 거친 점을 써서 차트마다 점이 수백 개 안팎이 되게 한다. */
    static String intervalFor(Duration range) {
        if (range.compareTo(Duration.ofHours(6)) <= 0) {
            return "1m";
        }
        if (range.compareTo(Duration.ofHours(24)) <= 0) {
            return "5m";
        }
        return "15m";
    }

    /** 컴파트먼트의 인스턴스별 최신 CPU. 그룹 쿼리 하나. */
    Map<String, Double> latestCpu(Collection<String> instanceIds) {
        Instant end = clock.instant();
        List<MetricData> data = summarize("CpuUtilization[1m].groupBy(resourceId).mean()", end.minus(CPU_WINDOW), end);
        Map<String, Double> result = new HashMap<>();
        for (MetricData d : data) {
            String id = d.getDimensions() == null ? null : d.getDimensions().get("resourceId");
            List<AggregatedDatapoint> points = d.getAggregatedDatapoints();
            if (id != null && instanceIds.contains(id) && points != null && !points.isEmpty()) {
                Double v = points.getLast().getValue();
                if (v != null) {
                    result.put(id, v);
                }
            }
        }
        return result;
    }

    /** 인스턴스 하나의 메트릭 하나에 대한 MQL. 예: {@code CpuUtilization[1m]{resourceId = "…"}.mean()}. */
    static String mql(String metric, String instanceId, String statistic) {
        return mql(metric, instanceId, statistic, "1m");
    }

    static String mql(String metric, String instanceId, String statistic, String interval) {
        return metric + "[" + interval + "]{resourceId = \"" + instanceId.replace("\"", "") + "\"}." + statistic + "()";
    }

    private List<MetricData> query(String metric, String instanceId, String statistic, Duration range, String interval) {
        Instant end = clock.instant();
        return summarize(mql(metric, instanceId, statistic, interval), end.minus(range), end, interval);
    }

    private List<MetricData> summarize(String mql, Instant start, Instant end) {
        return summarize(mql, start, end, "1m");
    }

    private List<MetricData> summarize(String mql, Instant start, Instant end, String resolution) {
        SummarizeMetricsDataDetails details = SummarizeMetricsDataDetails.builder()
                .namespace(NAMESPACE)
                .query(mql)
                .startTime(Date.from(start))
                .endTime(Date.from(end))
                .resolution(resolution)
                .build();
        return client.summarizeMetricsData(SummarizeMetricsDataRequest.builder()
                .compartmentId(compartmentId)
                .summarizeMetricsDataDetails(details)
                .build()).getItems();
    }

    /** 돌아온 (하나뿐인) 스트림을 샘플로 펼친다. 오래된 것부터. */
    static List<Metrics.Sample> series(List<MetricData> data) {
        List<Metrics.Sample> samples = new ArrayList<>();
        if (data == null) {
            return samples;
        }
        for (MetricData d : data) {
            if (d.getAggregatedDatapoints() == null) {
                continue;
            }
            for (AggregatedDatapoint p : d.getAggregatedDatapoints()) {
                if (p.getTimestamp() != null && p.getValue() != null) {
                    samples.add(new Metrics.Sample(p.getTimestamp().toInstant(), p.getValue()));
                }
            }
        }
        samples.sort(java.util.Comparator.comparing(Metrics.Sample::time));
        return samples;
    }
}
