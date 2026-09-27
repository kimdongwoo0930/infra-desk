package com.infradesk.provider.demo;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Creates {@link DemoProvider}s. Ignores secrets entirely. */
public class DemoProviderFactory implements CloudProviderFactory {

    private final Clock clock;
    private final Duration latency;

    public DemoProviderFactory(Clock clock, Duration latency) {
        this.clock = clock;
        this.latency = latency;
    }

    /** Real clock and a small latency so loading states are visible. */
    public DemoProviderFactory() {
        this(Clock.systemUTC(), Duration.ofMillis(400));
    }

    @Override
    public CloudProvider create(Account account, Map<String, String> secrets) {
        return new DemoProvider(account, clock, latency);
    }

    @Override
    public List<String> regions() {
        return DemoRegions.IDS;
    }
}
