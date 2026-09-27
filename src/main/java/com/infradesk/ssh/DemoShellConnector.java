package com.infradesk.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Fake shell for demo mode: no network. Answers a few common commands with canned output so the
 * terminal can be shown without real servers or keys.
 */
public class DemoShellConnector implements ShellConnector {

    private final Duration latency;
    private final Duration statsInterval;

    public DemoShellConnector(Duration latency, Duration statsInterval) {
        this.latency = latency;
        this.statsInterval = statsInterval;
    }

    public DemoShellConnector() {
        this(Duration.ofMillis(600), Duration.ofSeconds(ProcStats.INTERVAL_SECONDS));
    }

    @Override
    public ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows) {
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new DemoShellSession(target, hostnameOf(target));
    }

    /**
     * {@link ProcStats#COMMAND} streams fake /proc snapshots; anything else gets the same canned
     * answer the interactive demo shell would print, then exits.
     */
    @Override
    public ShellSession exec(SshTarget target, HostKeyPrompt prompt, String command) {
        if (command.equals(ProcStats.COMMAND)) {
            return new DemoStatsSession(target, hostnameOf(target), statsInterval);
        }
        if (command.equals(HostFacts.COMMAND)) {
            return new FinishedSession(target.address(), demoFacts(hostnameOf(target)), 0);
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String output = DemoShellSession.respond(command.strip(), target.username(), hostnameOf(target)).replace("\r\n", "\n");
        int exit = output.contains("command not found") ? 127 : 0;
        return new FinishedSession(target.address(), output, exit);
    }

    @Override
    public RemoteFiles sftp(SshTarget target, HostKeyPrompt prompt) {
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new DemoRemoteFiles(target.username(), hostnameOf(target));
    }

    /** Plausible HostFacts output that varies a little per host. */
    private static String demoFacts(String host) {
        int seed = Math.floorMod(host.hashCode(), 20);
        long totalKb = 100L * 1024 * 1024 * (seed % 2 == 0 ? 1 : 2) - 1024 * 1024;
        long usedKb = totalKb * (30 + seed * 2) / 100;
        String ports = seed % 3 == 0 ? "22 80 443" : seed % 3 == 1 ? "22 8080" : "22 80 443 3000";
        StringBuilder ss = new StringBuilder("State  Recv-Q Send-Q Local Address:Port Peer Address:Port\n");
        for (String p : ports.split(" ")) {
            ss.append("LISTEN 0 4096 0.0.0.0:").append(p).append(" 0.0.0.0:*\n");
        }
        ss.append("LISTEN 0 4096 127.0.0.1:6379 0.0.0.0:*\n");
        return "@uptime\n" + (86400L * (3 + seed) + 3600L * (seed % 24)) + ".42 1000.0\n"
                + "@os\nUbuntu 22.04.4 LTS\n"
                + "@disk\n/dev/sda1 " + totalKb + " " + usedKb + " " + (totalKb - usedKb) + " 47% /\n"
                + "@ports\n" + ss;
    }

    /** A command that already ran: its output, then EOF. */
    private record FinishedSession(String address, String text, int exit) implements ShellSession {
        @Override
        public InputStream output() {
            return new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream input() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public int waitFor() {
            return exit;
        }

        @Override
        public void close() {
        }
    }

    private static String hostnameOf(SshTarget target) {
        return target.host().startsWith("demo:") ? target.host().substring(5) : target.host();
    }

    static final class DemoShellSession implements ShellSession {

        private final SshTarget target;
        private final String hostname;
        private final PipedInputStream toTerminal;
        private final PipedOutputStream shellOut;
        private final PipedOutputStream terminalIn;
        private final PipedInputStream shellIn;
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean open = true;
        private volatile int columns = 80;

        DemoShellSession(SshTarget target, String hostname) {
            this.target = target;
            this.hostname = hostname;
            try {
                toTerminal = new PipedInputStream(64 * 1024);
                shellOut = new PipedOutputStream(toTerminal);
                shellIn = new PipedInputStream(4 * 1024);
                terminalIn = new PipedOutputStream(shellIn);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Thread.ofVirtual().name("demo-shell-" + hostname).start(this::loop);
        }

        private String prompt() {
            return "\u001b[1;32m" + target.username() + "@" + hostname + "\u001b[0m:\u001b[1;34m~\u001b[0m$ ";
        }

        private void loop() {
            try {
                write("Welcome to Ubuntu 22.04.4 LTS (GNU/Linux 6.5.0-1020-oracle aarch64)\r\n\r\n"
                        + "  \u001b[33m데모 모드 가짜 셸이에요.\u001b[0m 'help'로 사용할 수 있는 명령을 볼 수 있어요.\r\n\r\n"
                        + "Last login: Sun Sep 27 20:41:12 2026 from 198.51.100.7\r\n" + prompt());
                StringBuilder line = new StringBuilder();
                var reader = new java.io.InputStreamReader(shellIn, StandardCharsets.UTF_8);
                int c;
                while (open && (c = reader.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        write("\r\n");
                        String cmd = line.toString().strip();
                        line.setLength(0);
                        if (cmd.equals("exit") || cmd.equals("logout")) {
                            write("logout\r\n");
                            break;
                        }
                        write(respond(cmd, target.username(), hostname));
                        write(prompt());
                    } else if (c == 0x7f || c == 0x08) {
                        if (!line.isEmpty()) {
                            line.setLength(line.length() - 1);
                            write("\b \b");
                        }
                    } else if (c == 0x03) {
                        line.setLength(0);
                        write("^C\r\n" + prompt());
                    } else if (c == 0x0c) {
                        write("\u001b[H\u001b[2J" + prompt() + line);
                    } else if (c >= 0x20) {
                        line.append((char) c);
                        write(String.valueOf((char) c));
                    }
                }
            } catch (IOException ignored) {
                // Terminal closed.
            } finally {
                close();
            }
        }

        static String respond(String cmd, String username, String hostname) {
            if (cmd.isEmpty()) {
                return "";
            }
            String name = cmd.split("\\s+")[0];
            if (name.equals("sudo")) {
                return respond(cmd.substring(4).strip(), username, hostname);
            }
            return switch (name) {
                case "help" -> "사용할 수 있는 명령: whoami, hostname, uptime, free -h, df -h, ls, ps, docker ps, date, echo, clear, exit\r\n";
                case "whoami" -> username + "\r\n";
                case "hostname" -> hostname + "\r\n";
                case "uptime" -> " 21:04:33 up 14 days,  6:02,  1 user,  load average: 0.23, 0.18, 0.12\r\n";
                case "free" -> String.join("\r\n",
                        "               total        used        free      shared  buff/cache   available",
                        "Mem:            23Gi       5.1Gi        12Gi       2.0Mi       5.9Gi        17Gi",
                        "Swap:             0B          0B          0B", "");
                case "df" -> String.join("\r\n",
                        "Filesystem      Size  Used Avail Use% Mounted on",
                        "/dev/sda1        97G   45G   52G  47% /",
                        "tmpfs            12G     0   12G   0% /dev/shm", "");
                case "ls" -> "\u001b[1;34mapp\u001b[0m  \u001b[1;34mbackups\u001b[0m  docker-compose.yml  README.md\r\n";
                case "ps" -> String.join("\r\n",
                        "    PID TTY          TIME CMD",
                        "   1811 pts/0    00:00:00 bash",
                        "   1934 pts/0    00:00:00 ps", "");
                case "docker" -> cmd.startsWith("docker restart") ? cmd.substring(15).strip() + "\r\n"
                        : cmd.startsWith("docker logs") ? String.join("\r\n",
                        "[20:52:10] INFO  voice tracker tick · 12 users online",
                        "[20:53:10] INFO  voice tracker tick · 13 users online",
                        "[20:53:44] WARN  rate limited, retry in 1.2s",
                        "[20:54:10] INFO  voice tracker tick · 13 users online", "")
                        : String.join("\r\n",
                        "CONTAINER ID   IMAGE              STATUS        NAMES",
                        "3f2a1b9c8d7e   " + hostname + ":latest   Up 14 days    " + hostname,
                        "9a8b7c6d5e4f   redis:7-alpine     Up 14 days    redis", "");
                case "date" -> java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) + "\r\n";
                case "echo" -> cmd.substring(4).strip() + "\r\n";
                case "clear" -> "\u001b[H\u001b[2J";
                case "apt" -> "Reading package lists... Done\r\nAll packages are up to date. (데모)\r\n";
                case "du" -> "21G\t/var/lib/docker\r\n";
                default -> name + ": command not found (데모 셸)\r\n";
            };
        }

        private void write(String s) throws IOException {
            shellOut.write(s.getBytes(StandardCharsets.UTF_8));
            shellOut.flush();
        }

        @Override
        public InputStream output() {
            return toTerminal;
        }

        @Override
        public OutputStream input() {
            return terminalIn;
        }

        @Override
        public void resize(int columns, int rows) {
            this.columns = columns;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public int waitFor() throws InterruptedException {
            closed.await();
            return 0;
        }

        @Override
        public String address() {
            return target.address();
        }

        @Override
        public void close() {
            if (!open) {
                return;
            }
            open = false;
            try {
                shellOut.close();
                terminalIn.close();
            } catch (IOException ignored) {
            }
            closed.countDown();
        }
    }

    /** Emits /proc-shaped snapshots with plausible, slowly wandering counters. */
    static final class DemoStatsSession implements ShellSession {

        private final SshTarget target;
        private final PipedInputStream output;
        private final PipedOutputStream writer;
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean open = true;

        DemoStatsSession(SshTarget target, String hostname, Duration interval) {
            this.target = target;
            try {
                output = new PipedInputStream(64 * 1024);
                writer = new PipedOutputStream(output);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            int seed = hostname.hashCode();
            Thread.ofVirtual().name("demo-stats-" + hostname).start(() -> loop(seed, interval));
        }

        private void loop(int seed, Duration interval) {
            java.util.Random random = new java.util.Random(seed);
            long busy = 1_000_000;
            long idle = 9_000_000;
            long rx = 5_000_000_000L;
            long tx = 1_500_000_000L;
            long memTotal = 24_000_000;
            double cpuLevel = 0.15 + random.nextDouble() * 0.25;
            double memLevel = 0.35 + random.nextDouble() * 0.25;
            long ticks = Math.max(1, interval.toMillis() / 10); // 100 jiffies per second
            try {
                while (open) {
                    cpuLevel = Math.max(0.02, Math.min(0.95, cpuLevel + (random.nextDouble() - 0.5) * 0.12));
                    memLevel = Math.max(0.1, Math.min(0.9, memLevel + (random.nextDouble() - 0.5) * 0.02));
                    long dBusy = Math.round(ticks * 4 * cpuLevel);
                    busy += dBusy;
                    idle += ticks * 4 - dBusy;
                    rx += interval.toMillis() * (40 + random.nextInt(120));
                    tx += interval.toMillis() * (10 + random.nextInt(40));
                    long user = busy * 7 / 10;
                    long system = busy - user;
                    String block = "cpu  " + user + " 0 " + system + " " + idle + " 0 0 0 0 0 0\n"
                            + "MemTotal:       " + memTotal + " kB\n"
                            + "MemAvailable:   " + Math.round(memTotal * (1 - memLevel)) + " kB\n"
                            + "    lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0\n"
                            + "  ens3: " + rx + " 1 0 0 0 0 0 0 " + tx + " 1 0 0 0 0 0 0\n"
                            + "---\n";
                    writer.write(block.getBytes(StandardCharsets.UTF_8));
                    writer.flush();
                    Thread.sleep(interval);
                }
            } catch (IOException | InterruptedException ignored) {
                // Closed.
            } finally {
                close();
            }
        }

        @Override
        public InputStream output() {
            return output;
        }

        @Override
        public OutputStream input() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public int waitFor() throws InterruptedException {
            closed.await();
            return 0;
        }

        @Override
        public String address() {
            return target.address();
        }

        @Override
        public void close() {
            if (!open) {
                return;
            }
            open = false;
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            closed.countDown();
        }
    }
}
