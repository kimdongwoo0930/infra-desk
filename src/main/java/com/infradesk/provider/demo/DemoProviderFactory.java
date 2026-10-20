package com.infradesk.provider.demo;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** {@link DemoProvider}를 만든다. 비밀값은 전혀 쓰지 않는다. */
public class DemoProviderFactory implements CloudProviderFactory {

    private final Clock clock;
    private final Duration latency;

    public DemoProviderFactory(Clock clock, Duration latency) {
        this.clock = clock;
        this.latency = latency;
    }

    /** 실제 시계와 약간의 지연을 써서 로딩 상태가 보이게 한다. */
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
