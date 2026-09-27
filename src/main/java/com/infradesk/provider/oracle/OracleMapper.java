package com.infradesk.provider.oracle;

import com.infradesk.core.CloudProviderException;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceShapeConfig;
import com.oracle.bmc.model.BmcException;

/** Converts OCI SDK types into core types. Keeps OCI types inside this package. */
final class OracleMapper {

    private OracleMapper() {
    }

    static ServerStatus status(Instance.LifecycleState state) {
        if (state == null) {
            return ServerStatus.UNKNOWN;
        }
        return switch (state) {
            case Provisioning -> ServerStatus.PROVISIONING;
            case Starting -> ServerStatus.STARTING;
            case Running -> ServerStatus.RUNNING;
            case Stopping -> ServerStatus.STOPPING;
            case Stopped -> ServerStatus.STOPPED;
            case Terminating -> ServerStatus.TERMINATING;
            case Terminated -> ServerStatus.TERMINATED;
            // Moving and CreatingImage keep the instance running from the user's point of view.
            case Moving, CreatingImage -> ServerStatus.RUNNING;
            default -> ServerStatus.UNKNOWN;
        };
    }

    record Ips(String publicIp, String privateIp) {
        static final Ips NONE = new Ips(null, null);
    }

    static Server server(Instance instance, String accountId, Ips ips) {
        InstanceShapeConfig shape = instance.getShapeConfig();
        double cpus = shape != null && shape.getOcpus() != null ? shape.getOcpus() : 0;
        double mem = shape != null && shape.getMemoryInGBs() != null ? shape.getMemoryInGBs() : 0;
        return new Server(
                instance.getId(),
                accountId,
                instance.getDisplayName(),
                status(instance.getLifecycleState()),
                instance.getRegion(),
                instance.getShape(),
                cpus,
                mem,
                ips.publicIp(),
                ips.privateIp(),
                instance.getTimeCreated() == null ? null : instance.getTimeCreated().toInstant());
    }

    /** Turns SDK failures into user-facing Korean messages without leaking request details. */
    static CloudProviderException error(String action, RuntimeException e) {
        if (e instanceof BmcException bmc) {
            String reason = switch (bmc.getStatusCode()) {
                case 401 -> "인증에 실패했어요. OCID, fingerprint, 개인키가 맞는지 확인하세요.";
                case 403, 404 -> "권한이 없거나 리소스를 찾을 수 없어요. 사용자 정책과 OCID를 확인하세요.";
                case 409 -> "서버가 다른 작업 중이에요. 잠시 뒤 다시 시도하세요.";
                case 429 -> "OCI 요청 한도를 넘었어요. 잠시 뒤 다시 시도하세요.";
                case -1 -> "OCI에 연결하지 못했어요. 네트워크와 리전을 확인하세요.";
                default -> "OCI 오류 (" + bmc.getStatusCode() + " " + bmc.getServiceCode() + ")";
            };
            return new CloudProviderException(action + " 실패: " + reason, e);
        }
        return new CloudProviderException(action + " 실패: " + e.getMessage(), e);
    }
}
