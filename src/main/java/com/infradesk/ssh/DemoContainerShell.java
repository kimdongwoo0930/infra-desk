package com.infradesk.ssh;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code docker exec -it}에 대한 데모 응답: 컨테이너 셸 또는 데이터베이스 콘솔. */
final class DemoContainerShell {

    private static final Pattern ID = Pattern.compile("exec -it ([0-9a-f]{12,64}) ");
    private static final String NOTE = "\u001b[33m데모 모드 가짜 컨테이너예요.\u001b[0m\r\n";

    private DemoContainerShell() {
    }

    static DemoShellConnector.DemoShellSession.Mode modeFor(String command) {
        Matcher m = ID.matcher(command);
        String id = m.find() ? m.group(1).substring(0, 12) : "000000000000";
        if (command.contains("mysql -u root -p")) {
            return new DemoShellConnector.DemoShellSession.Mode(NOTE + "Enter password: \r\n"
                    + "Welcome to the MySQL monitor.  Commands end with ; or \\g.\r\n"
                    + "Server version: 8.4.2 MySQL Community Server - GPL\r\n\r\n",
                    "mysql> ", DemoContainerShell::mysql, Set.of("exit", "quit", "\\q", "exit;", "quit;"));
        }
        if (command.contains("psql")) {
            return new DemoShellConnector.DemoShellSession.Mode(NOTE + "psql (16.4)\r\nType \"help\" for help.\r\n\r\n",
                    "postgres=# ", cmd -> cmd.startsWith("\\l") || cmd.toLowerCase().startsWith("select")
                    ? " app | postgres | UTF8\r\n(1 row)\r\n\r\n" : "", Set.of("\\q", "exit", "quit"));
        }
        if (command.contains("redis-cli")) {
            return new DemoShellConnector.DemoShellSession.Mode(NOTE, "127.0.0.1:6379> ", DemoContainerShell::redis,
                    Set.of("exit", "quit"));
        }
        if (command.contains("mongosh")) {
            return new DemoShellConnector.DemoShellSession.Mode(NOTE + "Current Mongosh Log ID: 66f8…\r\n",
                    "test> ", cmd -> cmd.startsWith("show dbs") ? "admin   40.00 KiB\r\napp    120.00 KiB\r\n" : "",
                    Set.of("exit", "quit", "exit()"));
        }
        return new DemoShellConnector.DemoShellSession.Mode(NOTE, "root@" + id + ":/# ", DemoContainerShell::shell,
                Set.of("exit", "logout"));
    }

    private static String shell(String cmd) {
        String name = cmd.split("\\s+")[0];
        return switch (name) {
            case "ls" -> "bin  boot  data  dev  etc  home  lib  proc  root  run  srv  tmp  usr  var\r\n";
            case "whoami" -> "root\r\n";
            case "pwd" -> "/\r\n";
            case "ps" -> "  PID TTY          TIME CMD\r\n    1 ?        00:02:11 app\r\n   42 pts/0    00:00:00 bash\r\n";
            case "env" -> "HOSTNAME=demo\r\nPATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\r\n";
            case "cat" -> cmd.contains("os-release") ? "PRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\r\n" : "";
            default -> "bash: " + name + ": command not found\r\n";
        };
    }

    private static String mysql(String cmd) {
        String c = cmd.toLowerCase().replace(";", "").strip();
        if (c.equals("show databases")) {
            return String.join("\r\n",
                    "+--------------------+", "| Database           |", "+--------------------+",
                    "| app                |", "| information_schema |", "| mysql              |",
                    "| performance_schema |", "+--------------------+", "4 rows in set (0.00 sec)", "", "");
        }
        if (c.startsWith("select")) {
            return String.join("\r\n", "+----------+", "| count(*) |", "+----------+", "|     1284 |",
                    "+----------+", "1 row in set (0.01 sec)", "", "");
        }
        return c.isEmpty() ? "" : "Query OK, 0 rows affected (0.00 sec)\r\n\r\n";
    }

    private static String redis(String cmd) {
        String c = cmd.toLowerCase();
        if (c.equals("ping")) {
            return "PONG\r\n";
        }
        if (c.startsWith("dbsize")) {
            return "(integer) 42\r\n";
        }
        if (c.startsWith("keys")) {
            return "1) \"session:8f2a\"\r\n2) \"rate:198.51.100.7\"\r\n3) \"cache:home\"\r\n";
        }
        return c.isEmpty() ? "" : "OK\r\n";
    }
}
