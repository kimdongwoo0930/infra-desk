package com.infradesk.ssh;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docker logs --timestamps}가 돌려준 로그의 한 줄. 화면이 줄마다 시각, 수준(정보·경고·오류),
 * 내용을 나눠 보여 주려고 해석한 결과다. 서버에는 아무것도 보내지 않고, 이미 받은 텍스트만 읽는다.
 *
 * <p><b>수준은 추측이다.</b> Docker는 로그의 수준을 알려 주지 않고 앱마다 형식이 제각각이라, 줄 안의
 * 글자로 짐작한다. 틀릴 수 있다(예: 정상 줄에 "error"라는 단어가 들어 있으면 오류로 본다). 규칙은 아래와 같다.
 * <ul>
 *   <li>오류: 단어 {@code error, fatal, critical, panic, exception, traceback}, {@code level=error},
 *       또는 접속 기록의 HTTP 상태가 5xx</li>
 *   <li>경고: 단어 {@code warn, warning}, {@code level=warn}, 또는 접속 기록의 HTTP 상태가 4xx</li>
 *   <li>그 밖에는 정보</li>
 * </ul>
 * 오류 규칙이 경고 규칙보다 먼저다. 단어는 대소문자를 구분하지 않고 단어 단위로만 맞춘다
 * ({@code errors}, {@code terror}는 해당하지 않는다).
 *
 * @param time    Docker가 줄 앞에 붙인 시각. 붙어 있지 않거나 읽을 수 없으면 null(예: 스택 트레이스의 이어지는 줄)
 * @param level   짐작한 수준
 * @param message 시각을 뺀 내용
 */
public record LogLine(Instant time, Level level, String message) {

    /** 로그 줄의 수준. 화면에 보이는 이름을 함께 가진다. */
    public enum Level {
        INFO("정보"), WARN("경고"), ERROR("오류");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        /** 화면에 보이는 한국어 이름. */
        public String label() {
            return label;
        }
    }

    private static final Pattern ERROR_WORD = Pattern.compile(
            "\\b(error|fatal|critical|panic|exception|traceback)\\b|level=error", Pattern.CASE_INSENSITIVE);
    private static final Pattern WARN_WORD = Pattern.compile(
            "\\b(warn|warning)\\b|level=warn", Pattern.CASE_INSENSITIVE);
    /** 접속 기록의 요청 뒤에 오는 상태 코드: {@code "GET /x HTTP/1.1" 404 153}. */
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP/\\d(?:\\.\\d)?\"\\s+([1-5]\\d\\d)\\b");

    /** 로그 한 줄을 해석한다. 시각이 없는 줄도 받는다. */
    public static LogLine parse(String raw) {
        String line = raw.stripTrailing();
        Instant time = null;
        String message = line;
        int space = line.indexOf(' ');
        // 시각은 맨 앞 첫 낱말이다: 2025-10-27T06:30:41.123456789Z
        if (space > 0) {
            Instant parsed = parseTime(line.substring(0, space));
            if (parsed != null) {
                time = parsed;
                message = line.substring(space + 1);
            }
        }
        return new LogLine(time, classify(message), message);
    }

    /** 로그 전체를 줄 단위로 해석한다. 빈 줄은 건너뛴다. */
    public static List<LogLine> parseAll(String output) {
        List<LogLine> lines = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            if (!raw.isBlank()) {
                lines.add(parse(raw));
            }
        }
        return lines;
    }

    private static Instant parseTime(String token) {
        // 날짜 모양이 아닌 낱말은 빨리 걸러서, 일반 문장마다 예외를 만들지 않는다.
        if (token.length() < 20 || token.charAt(4) != '-' || token.charAt(10) != 'T') {
            return null;
        }
        try {
            return Instant.parse(token);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(token).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static Level classify(String message) {
        Matcher http = HTTP_STATUS.matcher(message);
        int status = http.find() ? Integer.parseInt(http.group(1)) : 0;
        if (ERROR_WORD.matcher(message).find() || status >= 500) {
            return Level.ERROR;
        }
        if (WARN_WORD.matcher(message).find() || status >= 400) {
            return Level.WARN;
        }
        return Level.INFO;
    }
}
