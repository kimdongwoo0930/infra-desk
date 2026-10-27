package com.infradesk.ui;

import com.infradesk.ssh.OpenConnection;
import com.infradesk.ssh.Purpose;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 접속 풀이 열어 둔 SSH 연결({@link OpenConnection})을 사람이 읽는 글자로 바꾼다. 상태 막대("SSH 연결 3 · 조회 2 ·
 * 파일 1")와 설정의 "지금 열린 SSH 연결" 표가 같은 규칙을 쓴다. 화면 없이 계산만 해서 단위 테스트가 있다.
 */
public final class ConnectionSummary {

    private ConnectionSummary() {
    }

    /** 용도의 한국어 이름. */
    public static String label(Purpose purpose) {
        return switch (purpose) {
            case QUERY -> "조회";
            case COMMAND -> "명령";
            case FILES -> "파일";
            case TRANSFER -> "전송";
        };
    }

    /**
     * 상태 막대 글자: 연결이 없으면 "SSH 연결 없음", 있으면 "SSH 연결 3 · 조회 2 · 파일 1"(0인 용도는 뺀다).
     * 터미널 탭은 풀 밖의 연결이라 여기에 세지 않는다(따로 "터미널 탭 N"으로 보여 준다).
     */
    public static String statusBar(List<OpenConnection> connections) {
        if (connections.isEmpty()) {
            return "SSH 연결 없음";
        }
        List<String> parts = new ArrayList<>();
        parts.add("SSH 연결 " + connections.size());
        for (Purpose purpose : Purpose.values()) {
            long n = connections.stream().filter(c -> c.purpose() == purpose).count();
            if (n > 0) {
                parts.add(label(purpose) + " " + n);
            }
        }
        return String.join(" · ", parts);
    }

    /** 가장 오래된 연결이 얼마나 되었는지: "방금", "12분째", "3시간 12분째", "2일째". */
    public static String elapsed(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 1) {
            return "방금 맺음";
        }
        if (minutes < 60) {
            return minutes + "분째";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "시간" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "분") + "째";
        }
        return hours / 24 + "일째";
    }

    /**
     * 표의 "상태" 칸: 이 서버의 연결들 중 가장 오래된 것의 나이와 쓰는 용도. 연결이 없으면 "연결 없음".
     * 예) "3시간 12분째 · 조회·파일"
     */
    public static String rowStatus(List<OpenConnection> mine, Instant now) {
        if (mine.isEmpty()) {
            return "연결 없음";
        }
        Instant oldest = mine.stream().map(OpenConnection::openedAt).min(Instant::compareTo).orElse(now);
        List<String> purposes = new ArrayList<>();
        for (Purpose purpose : Purpose.values()) {
            if (mine.stream().anyMatch(c -> c.purpose() == purpose)) {
                purposes.add(label(purpose));
            }
        }
        return elapsed(Duration.between(oldest, now)) + " · " + String.join("·", purposes);
    }
}
