package com.infradesk.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * java.util.logging 설정: 로그 디렉터리의 순환 파일(5 × 2 MB)과 stderr. 우리 코드는 INFO,
 * 라이브러리(slf4j-jdk14로 연결)는 WARNING 이상만 기록한다. 모든 줄이 {@link #redact}를 거치므로
 * 로그를 공유해도 되도록 개인키, 웹훅 URL, 공인 IP, OCID가 가려진다.
 */
public final class Logging {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", Pattern.DOTALL);
    private static final Pattern PEM_OPEN = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*");
    private static final Pattern WEBHOOK = Pattern.compile(
            "https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/[^\\s\"']+");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    private static final Pattern OCID = Pattern.compile("\\b(ocid1\\.[a-z]+\\.[a-z0-9]+\\.[a-z0-9-]*\\.)([a-z0-9]{8,})([a-z0-9]{6})\\b");

    private static Path logFile;
    /** 레벨 설정이 로거와 함께 가비지 컬렉션되지 않도록 강한 참조를 유지한다. */
    private static final java.util.List<Logger> QUIET = new java.util.ArrayList<>();

    private Logging() {
    }

    /** 시작할 때 핸들러를 한 번 설치한다. 파일을 열지 못하면 stderr 로깅만 남는다. */
    public static void install(Path dir) {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(Level.WARNING);
        Logger.getLogger("com.infradesk").setLevel(Level.INFO);
        // 해롭지 않은 것으로 알려진 OCI SDK 안내문. 클라이언트/다이얼로그마다 기록된다. 호출하지 않는
        // VPN 장치 설정 API의 스트림 닫기 팁과, OCI 인스턴스에서만 해당되는 IMDS 안내가 그것이다.
        for (String noisy : new String[] {"com.oracle.bmc.core.VirtualNetworkClient", "com.oracle.bmc.Region"}) {
            QUIET.add(Logger.getLogger(noisy));
        }
        QUIET.forEach(l -> l.setLevel(Level.SEVERE));

        Formatter formatter = new LineFormatter();
        Handler console = new ConsoleHandler();
        console.setLevel(Level.ALL);
        console.setFormatter(formatter);
        root.addHandler(console);
        try {
            Files.createDirectories(dir);
            FileHandler file = new FileHandler(dir.resolve("infradesk-%g.log").toString(), 2 * 1024 * 1024, 5, true);
            file.setEncoding("UTF-8");
            file.setLevel(Level.ALL);
            file.setFormatter(formatter);
            root.addHandler(file);
            logFile = dir.resolve("infradesk-0.log");
        } catch (IOException | SecurityException e) {
            root.log(Level.WARNING, "Log file unavailable in " + dir + ": " + e.getMessage());
        }
    }

    /** 현재 로그 파일. stderr만 쓰면 null. */
    public static Path logFile() {
        return logFile;
    }

    /** 비밀값과 식별 값을 가린다. 테스트를 위해 package-private. */
    static String redact(String text) {
        if (text == null) {
            return null;
        }
        String s = PEM.matcher(text).replaceAll("[private key hidden]");
        s = PEM_OPEN.matcher(s).replaceAll("[private key hidden]");
        s = WEBHOOK.matcher(s).replaceAll("[discord webhook hidden]");
        Matcher ocid = OCID.matcher(s);
        s = ocid.replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…" + m.group(3)));
        Matcher ip = IPV4.matcher(s);
        StringBuilder out = new StringBuilder();
        while (ip.find()) {
            ip.appendReplacement(out, Matcher.quoteReplacement(isPublic(ip) ? "x.x.x." + ip.group(4) : ip.group()));
        }
        ip.appendTail(out);
        return out.toString();
    }

    private static boolean isPublic(Matcher m) {
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            o[i] = Integer.parseInt(m.group(i + 1));
            if (o[i] > 255) {
                return false;
            }
        }
        return !(o[0] == 10 || o[0] == 127 || o[0] == 0 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168) || (o[0] == 100 && o[1] >= 64 && o[1] <= 127));
    }

    /** "2026-09-28 14:31:02.123 INFO  [thread] Logger: message" + 가려진 스택 트레이스. */
    private static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder();
            sb.append(TIME.format(Instant.ofEpochMilli(r.getMillis()))).append(' ')
                    .append(String.format("%-7s", r.getLevel().getName())).append(' ')
                    .append('[').append(threadName()).append("] ")
                    .append(shortName(r.getLoggerName())).append(": ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return redact(sb.toString());
        }

        /** 가상 스레드는 이름이 없으므로 "[]" 대신 "virtual-<id>"로 표시한다. */
        private static String threadName() {
            Thread t = Thread.currentThread();
            return t.getName().isEmpty() ? (t.isVirtual() ? "virtual-" : "thread-") + t.threadId() : t.getName();
        }

        private static String shortName(String name) {
            if (name == null || name.isEmpty()) {
                return "root";
            }
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }
}
