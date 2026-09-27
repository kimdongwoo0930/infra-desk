package com.infradesk.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshConfigTest {

    @TempDir
    Path home;

    private SshConfig config(String... lines) throws Exception {
        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve(".ssh/bot.key"), "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n");
        Files.writeString(home.resolve(".ssh/default.key"), "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n");
        return SshConfig.parse(List.of(lines), home);
    }

    @Test
    void findsHostByHostNameAndExpandsTilde() throws Exception {
        var s = config(
                "Host other", "    HostName 198.51.100.1", "    User root",
                "Host bot", "    HostName 203.0.113.24", "    User ubuntu", "    Port 2222",
                "    IdentityFile ~/.ssh/bot.key").suggest("203.0.113.24", "discord-bot").orElseThrow();
        assertEquals("bot", s.alias());
        assertEquals("ubuntu", s.user());
        assertEquals(2222, s.port());
        assertEquals(home.resolve(".ssh/bot.key"), s.identityFile());
    }

    @Test
    void firstValueWinsAndWildcardBlocksProvideDefaults() throws Exception {
        var s = config(
                "Host bot", "  HostName 203.0.113.24",
                "Host *", "  User opc", "  IdentityFile ~/.ssh/missing.key", "  IdentityFile ~/.ssh/default.key",
                "Host bot", "  User ignored-because-later").suggest("203.0.113.24", "x").orElseThrow();
        assertEquals("opc", s.user());
        assertEquals(home.resolve(".ssh/default.key"), s.identityFile(), "first existing IdentityFile");
        assertNull(s.port());
    }

    @Test
    void fallsBackToServerNameAlias() throws Exception {
        var s = config("Host discord-bot", "  User ubuntu").suggest("203.0.113.99", "discord-bot");
        assertTrue(s.isPresent());
        assertEquals("ubuntu", s.get().user());
    }

    @Test
    void negatedPatternExcludes() throws Exception {
        var s = config("Host bot", "  HostName 203.0.113.24", "Host * !bot", "  User wrong")
                .suggest("203.0.113.24", "x").orElseThrow();
        assertNull(s.user());
    }

    @Test
    void equalsSyntaxAndQuotesAndComments() throws Exception {
        var s = config("# comment", "Host=bot", "HostName = 203.0.113.24", "IdentityFile \"~/.ssh/bot.key\"")
                .suggest("203.0.113.24", "x").orElseThrow();
        assertEquals(home.resolve(".ssh/bot.key"), s.identityFile());
    }

    @Test
    void noMatchIsEmpty() throws Exception {
        assertTrue(config("Host a", "  HostName 1.2.3.4").suggest("5.6.7.8", "b").isEmpty());
    }
}
