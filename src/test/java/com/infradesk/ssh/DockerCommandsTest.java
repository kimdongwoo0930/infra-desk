package com.infradesk.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerCommandsTest {

    private static final String ID1 = "3f2a1b9c8d7e" + "0".repeat(52);
    private static final String ID2 = "9a8b7c6d5e4f" + "1".repeat(52);

    @Test
    void parsesContainersAndStats() {
        String out = String.join("\n",
                "@@ps",
                "{\"ID\":\"" + ID2 + "\",\"Names\":\"worker\",\"Image\":\"app:1\",\"State\":\"exited\",\"Status\":\"Exited (1) 2 hours ago\",\"Ports\":\"\"}",
                "{\"ID\":\"" + ID1 + "\",\"Names\":\"web\",\"Image\":\"nginx:1.27\",\"State\":\"running\",\"Status\":\"Up 3 days\",\"Ports\":\"0.0.0.0:80->80/tcp, :::80->80/tcp\"}",
                "@@stats",
                "{\"ID\":\"" + ID1.substring(0, 12) + "\",\"Name\":\"web\",\"CPUPerc\":\"1.25%\",\"MemUsage\":\"120MiB / 23.4GiB\",\"MemPerc\":\"0.50%\"}");
        DockerCommands.Listing l = DockerCommands.parseList(out);
        assertEquals(DockerCommands.Listing.Status.OK, l.status());
        List<Container> c = l.containers();
        assertEquals("web", c.getFirst().name(), "running first");
        assertEquals(1.25, c.getFirst().cpuPercent(), 1e-9);
        assertEquals("120MiB / 23.4GiB", c.getFirst().memUsage());
        assertEquals(-1, c.get(1).cpuPercent(), "no stats for stopped containers");
        assertFalse(c.get(1).isRunning());
    }

    @Test
    void reportsMissingDockerAndPermission() {
        assertEquals(DockerCommands.Listing.Status.NO_DOCKER, DockerCommands.parseList("@@nodocker\n").status());
        DockerCommands.Listing noAccess = DockerCommands.parseList("@@noaccess\n");
        assertEquals(DockerCommands.Listing.Status.NO_ACCESS, noAccess.status());
        assertTrue(noAccess.problem().contains("docker 그룹"));
        assertEquals(DockerCommands.Listing.Status.ERROR, DockerCommands.parseList("bash: oops").status());
    }

    @Test
    void onlyHexIdsReachTheShell() {
        assertTrue(DockerCommands.action(DockerCommands.Action.RESTART, ID1).endsWith("$D restart " + ID1));
        for (String bad : new String[] {"web", "abc; rm -rf /", ID1 + " && reboot", "$(id)", "", null, "ABCDEF123456"}) {
            assertThrows(IllegalArgumentException.class, () -> DockerCommands.action(DockerCommands.Action.STOP, bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> DockerCommands.logs(bad, 100), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> DockerCommands.logs(ID1, 999999));
    }

    @Test
    void preludeFallsBackToSudoAndNeverUsesTcp() {
        assertTrue(DockerCommands.PRELUDE.contains("sudo -n docker"));
        assertFalse(DockerCommands.list().contains("-H "), "no remote Docker host / TCP");
        assertFalse(DockerCommands.list().contains("2375"));
    }

    @org.junit.jupiter.api.Test
    void shellAndConsolesRunInsideTheContainerWithAPty() {
        String id = "a".repeat(64);
        String shell = DockerCommands.shell(id);
        org.junit.jupiter.api.Assertions.assertTrue(shell.startsWith(DockerCommands.PRELUDE));
        org.junit.jupiter.api.Assertions.assertTrue(shell.endsWith("exec $D exec -it " + id
                + " sh -c 'if command -v bash >/dev/null 2>&1; then exec bash; else exec sh; fi'"), shell);
        String psql = DockerCommands.console(id, DockerCommands.Console.POSTGRES);
        org.junit.jupiter.api.Assertions.assertTrue(psql.endsWith("sh -c 'exec psql -U \"${POSTGRES_USER:-postgres}\"'"), psql);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DockerCommands.shell("abc; rm -rf /"));
    }

    @org.junit.jupiter.api.Test
    void consoleIsPickedFromTheImageName() {
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(DockerCommands.Console.MYSQL),
                DockerCommands.Console.forImage("mysql:8.4"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(DockerCommands.Console.MYSQL),
                DockerCommands.Console.forImage("bitnami/mariadb:11"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(DockerCommands.Console.POSTGRES),
                DockerCommands.Console.forImage("postgres:16-alpine"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(DockerCommands.Console.POSTGRES),
                DockerCommands.Console.forImage("bitnami/postgresql@sha256:abc"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(DockerCommands.Console.REDIS),
                DockerCommands.Console.forImage("redis/redis-stack-server:latest"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(DockerCommands.Console.MONGO),
                DockerCommands.Console.forImage("mongo"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.empty(),
                DockerCommands.Console.forImage("nginx:1.27"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(DockerCommands.Console.MYSQL),
                DockerCommands.Console.forImage("mysql/mysql-server:8.0"));
        for (String notADatabase : new String[] {"prom/mysqld-exporter", "oliver006/redis_exporter", "mongo-express",
                "rediscommander/redis-commander", "redis/redisinsight", "bitnami/postgres-exporter"}) {
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.empty(),
                    DockerCommands.Console.forImage(notADatabase), notADatabase);
        }
    }
}
