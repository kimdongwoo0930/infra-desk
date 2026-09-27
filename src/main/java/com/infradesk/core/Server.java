package com.infradesk.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider-neutral view of a server. Provider SDK types are converted into this record and never
 * leak past the provider package.
 *
 * @param id        provider-side identifier (e.g. an OCI instance OCID)
 * @param accountId owning {@link Account#id()}
 * @param name      display name
 * @param status    normalized lifecycle state
 * @param region    region identifier
 * @param shape     machine type, e.g. "VM.Standard.A1.Flex"
 * @param cpuCount  vCPU/OCPU count, 0 when unknown
 * @param memoryGb  memory in GB, 0 when unknown
 * @param publicIp  public IPv4, or null
 * @param privateIp private IPv4, or null
 * @param createdAt creation time, or null
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
