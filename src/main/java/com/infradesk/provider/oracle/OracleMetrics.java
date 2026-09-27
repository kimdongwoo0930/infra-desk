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
 * Reads instance metrics from OCI Monitoring ({@code oci_computeagent} namespace, reported by the
 * Compute Instance Monitoring plugin). OCI aggregates at one-minute resolution.
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

    /** Last hour of CPU, memory and network for one instance. Four queries. */
    Metrics forInstance(String instanceId) {
        return new Metrics(
                series(query("CpuUtilization", instanceId, "mean")),
                series(query("MemoryUtilization", instanceId, "mean")),
                series(query("NetworksBytesIn", instanceId, "rate")),
                series(query("NetworksBytesOut", instanceId, "rate")));
    }

    /** Latest CPU per instance in the compartment, one grouped query. */
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

    /** MQL for one metric of one instance, e.g. {@code CpuUtilization[1m]{resourceId = "…"}.mean()}. */
    static String mql(String metric, String instanceId, String statistic) {
        return metric + "[1m]{resourceId = \"" + instanceId.replace("\"", "") + "\"}." + statistic + "()";
    }

    private List<MetricData> query(String metric, String instanceId, String statistic) {
        Instant end = clock.instant();
        return summarize(mql(metric, instanceId, statistic), end.minus(WINDOW), end);
    }

    private List<MetricData> summarize(String mql, Instant start, Instant end) {
        SummarizeMetricsDataDetails details = SummarizeMetricsDataDetails.builder()
                .namespace(NAMESPACE)
                .query(mql)
                .startTime(Date.from(start))
                .endTime(Date.from(end))
                .resolution("1m")
                .build();
        return client.summarizeMetricsData(SummarizeMetricsDataRequest.builder()
                .compartmentId(compartmentId)
                .summarizeMetricsDataDetails(details)
                .build()).getItems();
    }

    /** Flattens the (single) returned stream into samples, oldest first. */
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
