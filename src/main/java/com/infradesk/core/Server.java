package com.infradesk.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * provider와 무관한 서버 뷰. provider SDK 타입은 이 record로 변환되며 provider 패키지 밖으로
 * 새어 나가지 않는다.
 *
 * @param id        provider 쪽 식별자(예: OCI 인스턴스 OCID)
 * @param accountId 소유한 {@link Account#id()}
 * @param name      표시 이름
 * @param status    정규화된 생명주기 상태
 * @param region    리전 식별자
 * @param shape     머신 유형. 예: "VM.Standard.A1.Flex"
 * @param cpuCount  vCPU/OCPU 수. 알 수 없으면 0
 * @param memoryGb  메모리(GB). 알 수 없으면 0
 * @param publicIp  공인 IPv4. 없으면 null
 * @param privateIp 사설 IPv4. 없으면 null
 * @param createdAt 생성 시각. 없으면 null
 */
public record Server(
        String id,
        String accountId,
        String name,
        ServerStatus status,
        String region,
        String shape,
        double cpuCount,
        double memoryGb,
        String publicIp,
        String privateIp,
        Instant createdAt) {

    public Server {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        status = status == null ? ServerStatus.UNKNOWN : status;
    }

    public Optional<String> publicIpAddress() {
        return Optional.ofNullable(publicIp);
    }

    public Optional<String> privateIpAddress() {
        return Optional.ofNullable(privateIp);
    }

    public Server withStatus(ServerStatus newStatus) {
        return new Server(id, accountId, name, newStatus, region, shape, cpuCount, memoryGb,
                publicIp, privateIp, createdAt);
    }
}
