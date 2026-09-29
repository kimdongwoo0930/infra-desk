package com.infradesk.provider.oracle;

import com.infradesk.core.Metrics;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OracleMetricsTest {

    @Test
    void buildsMqlForOneInstance() {
        assertEquals("CpuUtilization[1m]{resourceId = \"ocid1.instance.oc1..x\"}.mean()",
                OracleMetrics.mql("CpuUtilization", "ocid1.instance.oc1..x", "mean"));
    }

    @Test
    void longerRangesUseCoarserIntervals() {
        assertEquals("1m", OracleMetrics.intervalFor(java.time.Duration.ofHours(1)));
        assertEquals("1m", OracleMetrics.intervalFor(java.time.Duration.ofHours(6)));
        assertEquals("5m", OracleMetrics.intervalFor(java.time.Duration.ofHours(24)));
        assertEquals("15m", OracleMetrics.intervalFor(java.time.Duration.ofDays(7)));
        assertEquals("CpuUtilization[5m]{resourceId = \"x\"}.mean()",
                OracleMetrics.mql("CpuUtilization", "x", "mean", "5m"));
    }

    @Test
    void quotesInIdsCannotBreakTheQuery() {
        assertEquals("CpuUtilization[1m]{resourceId = \"abc\"}.mean()",
                OracleMetrics.mql("CpuUtilization", "a\"b\"c", "mean"));
    }

    @Test
    void flattensAndSortsDatapointsSkippingNulls() {
        MetricData d = MetricData.builder()
                .aggregatedDatapoints(List.of(
                        AggregatedDatapoint.builder().timestamp(new Date(120_000)).value(30.0).build(),
                        AggregatedDatapoint.builder().timestamp(new Date(60_000)).value(10.0).build(),
                        AggregatedDatapoint.builder().timestamp(new Date(180_000)).value(null).build()))
                .build();
        List<Metrics.Sample> samples = OracleMetrics.series(List.of(d));
        assertEquals(2, samples.size());
        assertEquals(10.0, samples.getFirst().value());
        assertEquals(30.0, samples.getLast().value());
    }
}
