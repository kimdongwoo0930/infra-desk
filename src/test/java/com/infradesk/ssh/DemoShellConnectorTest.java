package com.infradesk.ssh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoShellConnectorTest {

    /** Reads until the text appears or 2 seconds pass. */
    private static String readUntil(InputStream in, String needle) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        byte[] buf = new byte[1024];
        while (!sb.toString().contains(needle) && System.nanoTime() < deadline) {
            if (in.available() > 0) {
                int n = in.read(buf);
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } else {
                Thread.sleep(10);
            }
        }
        return sb.toString();
    }

    @Test
    void answersCommandsAndExits() throws Exception {
        ShellSession shell = new DemoShellConnector(Duration.ZERO)
                .open(new SshTarget("demo:bot", 22, "ubuntu", "demo", null), (h, p, t, f) -> true, 80, 24);
        assertTrue(readUntil(shell.output(), "$ ").contains("ubuntu@bot"));

        shell.input().write("whoami\r".getBytes(StandardCharsets.UTF_8));
        shell.input().flush();
        assertTrue(readUntil(shell.output(), "ubuntu\r\n").contains("ubuntu\r\n"));

        shell.input().write("exit\r".getBytes(StandardCharsets.UTF_8));
        shell.input().flush();
        shell.waitFor();
        assertFalse(shell.isOpen());
    }
}
