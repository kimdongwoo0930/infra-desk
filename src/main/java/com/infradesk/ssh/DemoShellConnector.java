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

    public DemoShellConnector(Duration latency) {
        this.latency = latency;
    }

    public DemoShellConnector() {
        this(Duration.ofMillis(600));
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
                        write(respond(cmd));
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

        private String respond(String cmd) {
            if (cmd.isEmpty()) {
                return "";
            }
            String name = cmd.split("\\s+")[0];
            return switch (name) {
                case "help" -> "사용할 수 있는 명령: whoami, hostname, uptime, free -h, df -h, ls, ps, docker ps, date, echo, clear, exit\r\n";
                case "whoami" -> target.username() + "\r\n";
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
                case "docker" -> String.join("\r\n",
                        "CONTAINER ID   IMAGE              STATUS        NAMES",
                        "3f2a1b9c8d7e   " + hostname + ":latest   Up 14 days    " + hostname,
                        "9a8b7c6d5e4f   redis:7-alpine     Up 14 days    redis", "");
                case "date" -> java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) + "\r\n";
                case "echo" -> cmd.substring(4).strip() + "\r\n";
                case "clear" -> "\u001b[H\u001b[2J";
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
}
