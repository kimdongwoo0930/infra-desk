package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DemoShellConnector;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.storage.InMemorySavedCommandStore;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.InMemorySshSettingsStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerServiceTest {

    @Test
    void demoListStopStartAndLogs() {
        TerminalService terminal = new TerminalService(new InMemorySshSettingsStore(), new InMemorySecretStore(),
                new DemoShellConnector(Duration.ZERO, Duration.ofMillis(50)), new InMemorySavedCommandStore(List.of()), true);
        ContainerService docker = new ContainerService(terminal);
        Server server = new Server("s", "a", "docker-test-" + System.nanoTime(), ServerStatus.RUNNING, "r", "s", 1, 1, null, null, null);

        DockerCommands.Listing l = docker.list(server, (h, p, t, f) -> true);
        assertEquals(DockerCommands.Listing.Status.OK, l.status());
        Container redis = l.containers().stream().filter(c -> c.name().equals("redis")).findFirst().orElseThrow();
        assertTrue(redis.isRunning());
        assertTrue(redis.cpuPercent() >= 0);

        docker.act(server, redis, DockerCommands.Action.STOP, (h, p, t, f) -> true);
        Container stopped = docker.list(server, (h, p, t, f) -> true).containers().stream()
                .filter(c -> c.name().equals("redis")).findFirst().orElseThrow();
        assertFalse(stopped.isRunning());

        docker.act(server, stopped, DockerCommands.Action.START, (h, p, t, f) -> true);
        assertTrue(docker.list(server, (h, p, t, f) -> true).containers().stream()
                .filter(c -> c.name().equals("redis")).findFirst().orElseThrow().isRunning());

        assertTrue(docker.logs(server, redis, 200, (h, p, t, f) -> true).contains("redis"));
    }
}
