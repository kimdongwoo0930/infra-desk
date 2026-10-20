package com.infradesk.provider.oracle;

import com.infradesk.core.Account;
import com.infradesk.core.AccountSecrets;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.Metrics;
import com.infradesk.core.Server;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.InstanceActionRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.monitoring.MonitoringClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** OCI Java SDK로 구현한 {@link CloudProvider}. 테넌시마다 인스턴스 하나. */
public class OracleProvider implements CloudProvider {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private final Account account;
    private final String compartmentId;
    private final ComputeClient compute;
    private final VirtualNetworkClient network;
    private final MonitoringClient monitoring;
    private final OracleMetrics metrics;

    public OracleProvider(Account account, Map<String, String> secrets) {
        this(account, secrets, null);
    }

    /** @param endpointOverride 모든 클라이언트의 기본 URL(테스트 전용). null이면 리전 엔드포인트를 쓴다 */
    OracleProvider(Account account, Map<String, String> secrets, String endpointOverride) {
        this.account = account;
        String tenancy = require(account, OracleProperties.TENANCY_OCID);
        String privateKey = secrets.get(AccountSecrets.PRIVATE_KEY);
        if (privateKey == null || privateKey.isBlank()) {
            throw new CloudProviderException(account.displayName() + ": API 개인키가 없어요. 계정을 다시 등록하세요.");
        }
        Region region;
        try {
            region = Region.fromRegionId(account.region());
        } catch (IllegalArgumentException e) {
            throw new CloudProviderException("알 수 없는 리전이에요: " + account.region(), e);
        }
        byte[] keyBytes = privateKey.getBytes(StandardCharsets.UTF_8);
        var auth = SimpleAuthenticationDetailsProvider.builder()
                .tenantId(tenancy)
                .userId(require(account, OracleProperties.USER_OCID))
                .fingerprint(require(account, OracleProperties.FINGERPRINT))
                .region(region)
                .privateKeySupplier(() -> new ByteArrayInputStream(keyBytes))
                .build();
        this.compartmentId = Objects.requireNonNullElse(account.property(OracleProperties.COMPARTMENT_OCID), tenancy);
        // 시간 제한을 명시하지 않으면 끊어진 연결(잠자기, Wi-Fi 전환)이 요청을 오랫동안 붙잡아 둘 수 있다.
        var timeouts = com.oracle.bmc.ClientConfiguration.builder()
                .connectionTimeoutMillis(CONNECT_TIMEOUT_MS)
                .readTimeoutMillis(READ_TIMEOUT_MS)
                .build();
        this.compute = ComputeClient.builder().configuration(timeouts).build(auth);
        this.network = VirtualNetworkClient.builder().configuration(timeouts).build(auth);
        this.monitoring = MonitoringClient.builder().configuration(timeouts).build(auth);
        if (endpointOverride != null) {
            compute.setEndpoint(endpointOverride);
            network.setEndpoint(endpointOverride);
            monitoring.setEndpoint(endpointOverride);
        }
        this.metrics = new OracleMetrics(monitoring, compartmentId, java.time.Clock.systemUTC());
    }

    @Override
    public List<Server> listServers() {
        try {
            List<Instance> instances = new ArrayList<>();
            String page = null;
            do {
                ListInstancesResponse res = compute.listInstances(ListInstancesRequest.builder()
                        .compartmentId(compartmentId).page(page).build());
                instances.addAll(res.getItems());
                page = res.getOpcNextPage();
            } while (page != null);

            Map<String, OracleMapper.Ips> ips = primaryIps();
            List<Server> servers = new ArrayList<>();
            for (Instance i : instances) {
                if (i.getLifecycleState() == Instance.LifecycleState.Terminated) {
                    continue;
                }
                servers.add(OracleMapper.server(i, account.id(), ips.getOrDefault(i.getId(), OracleMapper.Ips.NONE)));
            }
            return servers;
        } catch (RuntimeException e) {
            throw OracleMapper.error("서버 목록 조회", e);
        }
    }

    /** 인스턴스 id → 기본 VNIC의 IP들. 컴파트먼트 전체에 대해 attachment 목록을 한 번만 조회한다. */
    private Map<String, OracleMapper.Ips> primaryIps() {
        Map<String, OracleMapper.Ips> result = new HashMap<>();
        String page = null;
        do {
            ListVnicAttachmentsResponse res = compute.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                    .compartmentId(compartmentId).page(page).build());
            for (VnicAttachment a : res.getItems()) {
                if (a.getLifecycleState() != VnicAttachment.LifecycleState.Attached || a.getVnicId() == null) {
                    continue;
                }
                Vnic vnic = network.getVnic(GetVnicRequest.builder().vnicId(a.getVnicId()).build()).getVnic();
                if (Boolean.TRUE.equals(vnic.getIsPrimary()) || !result.containsKey(a.getInstanceId())) {
                    result.put(a.getInstanceId(), new OracleMapper.Ips(vnic.getPublicIp(), vnic.getPrivateIp()));
                }
            }
            page = res.getOpcNextPage();
        } while (page != null);
        return result;
    }

    @Override
    public void start(String serverId) {
        action(serverId, "START", "서버 시작");
    }

    @Override
    public void stop(String serverId) {
        action(serverId, "SOFTSTOP", "서버 정지");
    }

    @Override
    public void reboot(String serverId) {
        action(serverId, "SOFTRESET", "서버 재부팅");
    }

    @Override
    public Metrics getMetrics(String serverId) {
        try {
            return metrics.forInstance(serverId);
        } catch (RuntimeException e) {
            throw OracleMapper.error("메트릭 조회", e);
        }
    }

    @Override
    public Metrics getMetrics(String serverId, java.time.Duration range) {
        try {
            return metrics.forInstance(serverId, range);
        } catch (RuntimeException e) {
            throw OracleMapper.error("메트릭 조회", e);
        }
    }

    @Override
    public Map<String, Double> currentCpu(List<Server> servers) {
        try {
            return metrics.latestCpu(servers.stream().map(Server::id).toList());
        } catch (RuntimeException e) {
            throw OracleMapper.error("CPU 사용률 조회", e);
        }
    }

    @Override
    public void close() {
        compute.close();
        network.close();
        monitoring.close();
    }

    private void action(String serverId, String action, String label) {
        try {
            compute.instanceAction(InstanceActionRequest.builder().instanceId(serverId).action(action).build());
        } catch (RuntimeException e) {
            throw OracleMapper.error(label, e);
        }
    }

    private static String require(Account account, String key) {
        String value = account.property(key);
        if (value == null || value.isBlank()) {
            throw new CloudProviderException(account.displayName() + ": 설정값이 없어요 (" + key + ")");
        }
        return value;
    }
}
