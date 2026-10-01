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
 * 데모 모드용 가짜 셸: 네트워크를 쓰지 않는다. 자주 쓰는 명령 몇 가지에 미리 준비한 출력으로
 * 답해서, 실제 서버나 키 없이 터미널을 보여줄 수 있다.
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

    /** 데모 컨테이너로 {@code docker exec -it}: 가짜 셸 또는 데이터베이스 콘솔. */
    @Override
    public ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows, String command) {
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new DemoShellSession(target, hostnameOf(target), DemoContainerShell.modeFor(command));
    }

    /**
     * {@link ProcStats#COMMAND}는 가짜 /proc 스냅샷을 스트리밍한다. 그 외 명령은 대화형 데모 셸이
     * 출력했을 것과 같은 준비된 답을 한 번 출력하고 종료한다.
     */
    @Override
    public ShellSession exec(SshTarget target, HostKeyPrompt prompt, String command) {
        if (command.equals(ProcStats.COMMAND)) {
            return new DemoStatsSession(target, hostnameOf(target), statsInterval);
        }
        if (command.equals(ProcStats.ONE_SHOT)) {
            return new FinishedSession(target.address(), demoOneShot(hostnameOf(target)), 0);
        }
        if (command.startsWith(DockerCommands.PRELUDE)) {
            return new FinishedSession(target.address(), DemoDocker.run(hostnameOf(target), command), 0);
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

    /** 1초 간격의 /proc 형식 블록 두 개. 호출마다 부하가 조금씩 변한다. */
    private static String demoOneShot(String host) {
        java.util.Random r = new java.util.Random();
        int seed = Math.floorMod(host.hashCode(), 20);
        double cpu = 0.08 + seed / 100.0 + r.nextDouble() * 0.15;
        long total = 400;
        long busy = Math.round(total * cpu);
        long memTotal = 24L * 1024 * 1024;
        long avail = Math.round(memTotal * (0.55 + r.nextDouble() * 0.1));
        long rx = 1_000_000_000L + r.nextInt(1000);
        String first = "cpu  1000 0 500 8500 0 0 0 0 0 0\nMemTotal: " + memTotal + " kB\nMemAvailable: " + avail + " kB\n"
                + "  eth0: " + rx + " 0 0 0 0 0 0 0 " + rx / 3 + " 0 0 0 0 0 0 0\n---\n";
        String second = "cpu  " + (1000 + busy) + " 0 500 " + (8500 + total - busy) + " 0 0 0 0 0 0\nMemTotal: " + memTotal
                + " kB\nMemAvailable: " + avail + " kB\n  eth0: " + (rx + 40_000 + r.nextInt(80_000)) + " 0 0 0 0 0 0 0 "
                + (rx / 3 + 12_000 + r.nextInt(30_000)) + " 0 0 0 0 0 0 0\n---\n";
        return first + second;
    }

    /** 호스트마다 조금씩 다른, 그럴듯한 HostFacts 출력. */
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
                + "@os\n" + ((host.contains("mac") || host.contains("맥")) ? "macOS 15.6" : "Ubuntu 22.04.4 LTS") + "\n"
                + "@cpus\n" + ((host.contains("mac") || host.contains("맥")) ? 10 : 4) + "\n@memkb\n" + ((host.contains("mac") || host.contains("맥")) ? 16L : 24L) * 1024 * 1024 + "\n"
                + "@disk\n/dev/sda1 " + totalKb + " " + usedKb + " " + (totalKb - usedKb) + " 47% /\n"
                + "@ports\n" + ss;
    }

    /** 이미 실행이 끝난 명령: 출력한 뒤 EOF. */
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

        /** 데모 세션이 인사하고, 프롬프트를 띄우고, 답하는 방식. {@code null} 필드는 호스트 셸을 뜻한다. */
        record Mode(String banner, String prompt, java.util.function.Function<String, String> respond,
                    java.util.Set<String> exitWords) {
        }

        private final Mode mode;

        DemoShellSession(SshTarget target, String hostname) {
            this(target, hostname, null);
        }

        DemoShellSession(SshTarget target, String hostname, Mode mode) {
            this.target = target;
            this.hostname = hostname;
            this.mode = mode;
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
            if (mode != null) {
                return mode.prompt();
            }
            return "\u001b[1;32m" + target.username() + "@" + hostname + "\u001b[0m:\u001b[1;34m~\u001b[0m$ ";
        }

        private void loop() {
            try {
                write((mode != null ? mode.banner()
                        : "Welcome to Ubuntu 22.04.4 LTS (GNU/Linux 6.5.0-1020-oracle aarch64)\r\n\r\n"
                        + "  \u001b[33m데모 모드 가짜 셸이에요.\u001b[0m 'help'로 사용할 수 있는 명령을 볼 수 있어요.\r\n\r\n"
                        + "Last login: Sun Sep 27 20:41:12 2026 from 198.51.100.7\r\n") + prompt());
                StringBuilder line = new StringBuilder();
                var reader = new java.io.InputStreamReader(shellIn, StandardCharsets.UTF_8);
                int c;
                while (open && (c = reader.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        write("\r\n");
                        String cmd = line.toString().strip();
                        line.setLength(0);
                        if (mode != null ? mode.exitWords().contains(cmd) : cmd.equals("exit") || cmd.equals("logout")) {
                            write(mode != null ? "Bye\r\n" : "logout\r\n");
                            break;
                        }
                        write(mode != null ? mode.respond().apply(cmd) : respond(cmd, target.username(), hostname));
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
                // 터미널이 닫혔다.
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

    /** /proc 형식의 스냅샷을 내보낸다. 카운터가 그럴듯하게 천천히 움직인다. */
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
            long ticks = Math.max(1, interval.toMillis() / 10); // 초당 100 jiffies
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
                // 닫혔다.
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
