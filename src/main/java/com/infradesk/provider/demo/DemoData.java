package com.infradesk.provider.demo;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fake accounts and servers for demo mode, mirroring docs/design mockups. IPs are from the
 * documentation ranges (RFC 5737), OCIDs are obviously fake.
 */
public final class DemoData {

    private DemoData() {
    }

    public static List<Account> accounts() {
        return List.of(
                account("demo-a", "계정 A", "ap-chuncheon-1"),
                account("demo-b", "계정 B", "ap-seoul-1"),
                account("demo-c", "계정 C", "ap-seoul-1"));
    }

    /** Initial servers for a demo account; unknown accounts (added in demo mode) get one generic server. */
    static List<Server> serversFor(Account account) {
        return switch (account.id()) {
            case "demo-a" -> List.of(
                    server(account, "discord-bot", ServerStatus.RUNNING,
                            "VM.Standard.A1.Flex", 2, 12, "203.0.113.24", "10.0.0.12", "2025-11-03T09:12:00Z"),
                    server(account, "api-gateway", ServerStatus.RUNNING,
                            "VM.Standard.A1.Flex", 1, 6, "203.0.113.31", "10.0.0.15", "2026-01-20T05:30:00Z"),
                    server(account, "batch-worker", ServerStatus.STOPPED,
                            "VM.Standard.A1.Flex", 1, 6, "203.0.113.45", "10.0.0.18", "2026-06-11T11:00:00Z"));
            case "demo-b" -> List.of(server(account, "web-server", ServerStatus.RUNNING,
                    "VM.Standard.A1.Flex", 2, 12, "198.51.100.41", "10.0.1.20", "2025-12-18T02:40:00Z"));
            case "demo-c" -> List.of(server(account, "test-box", ServerStatus.STOPPED,
                    "VM.Standard.E2.1.Micro", 1, 1, "192.0.2.77", "10.0.2.5", "2026-03-02T13:05:00Z"));
            default -> List.of(server(account, "new-server", ServerStatus.RUNNING,
                    "VM.Standard.A1.Flex", 1, 6, "192.0.2.10", "10.0.9.2", "2026-09-01T00:00:00Z"));
        };
    }

    private static Account account(String id, String name, String region) {
        return new Account(id, name, ProviderType.ORACLE, region, Map.of(
                "tenancyOcid", "ocid1.tenancy.oc1..demo" + id.substring(5),
                "userOcid", "ocid1.user.oc1..demo" + id.substring(5),
                "fingerprint", "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"));
    }

    private static Server server(Account account, String name, ServerStatus status, String shape,
                                 double cpus, double memGb, String publicIp, String privateIp, String created) {
        return new Server("ocid1.instance.oc1.demo." + account.id() + "." + name, account.id(), name, status,
                account.region(), shape, cpus, memGb, publicIp, privateIp, Instant.parse(created));
    }
}
