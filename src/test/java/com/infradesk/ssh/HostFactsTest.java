package com.infradesk.ssh;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HostFactsTest {

    @Test
    void parsesSsOutput() {
        String out = String.join("\n",
                "@uptime", "1231234.56 4567.89",
                "@os", "Ubuntu 22.04.4 LTS",
                "@disk", "/dev/sda1   101430960  47563212  53851364  47% /",
                "@ports",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      4096   0.0.0.0:22          0.0.0.0:*",
                "LISTEN 0      511    0.0.0.0:443         0.0.0.0:*",
                "LISTEN 0      4096   127.0.0.53%lo:53    0.0.0.0:*",
                "LISTEN 0      4096   127.0.0.1:6379      0.0.0.0:*",
                "LISTEN 0      4096   [::]:22             [::]:*",
                "LISTEN 0      4096   [::1]:5432          [::]:*",
                "LISTEN 0      511    *:80                *:*");
        HostFacts f = HostFacts.parse(out);
        assertEquals(Duration.ofSeconds(1231234), f.uptime());
        assertEquals("Ubuntu 22.04.4 LTS", f.osName());
        assertEquals(101430960L * 1024, f.diskTotal());
        assertEquals(47563212L * 1024, f.diskUsed());
        assertEquals(List.of(22, 80, 443), f.ports());
    }

    @Test
    void parsesNetstatFallback() {
        String out = String.join("\n", "@ports",
                "Active Internet connections (only servers)",
                "Proto Recv-Q Send-Q Local Address           Foreign Address         State",
                "tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN",
                "tcp        0      0 127.0.0.1:25            0.0.0.0:*               LISTEN",
                "tcp6       0      0 :::8080                 :::*                    LISTEN");
        assertEquals(List.of(22, 8080), HostFacts.parse(out).ports());
    }

    @Test
    void missingSectionsStayUnknown() {
        HostFacts f = HostFacts.parse("@uptime\n@os\n@disk\n@ports\n");
        assertNull(f.uptime());
        assertNull(f.osName());
        assertEquals(-1, f.diskTotal());
        assertEquals(List.of(), f.ports());
    }

    @Test
    void demoConnectorAnswersFactsCommand() throws Exception {
        var shell = new DemoShellConnector(Duration.ZERO, Duration.ofMillis(50))
                .exec(new SshTarget("demo:bot", 22, "ubuntu", "demo", null), (h, p, t, fp) -> true, HostFacts.COMMAND);
        HostFacts f = HostFacts.parse(new String(shell.output().readAllBytes()));
        assertEquals("Ubuntu 22.04.4 LTS", f.osName());
        assertEquals(22, f.ports().getFirst());
    }
}
