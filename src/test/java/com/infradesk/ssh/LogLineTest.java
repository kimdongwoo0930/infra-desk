package com.infradesk.ssh;

import com.infradesk.ssh.LogLine.Level;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogLineTest {

    private static Level level(String message) {
        return LogLine.parse("2025-10-27T06:30:41.123456789Z " + message).level();
    }

    @Test
    void 맨_앞의_시각과_내용을_나눈다() {
        LogLine line = LogLine.parse("2025-10-27T06:30:41.123456789Z GET /health 200");

        assertEquals(Instant.parse("2025-10-27T06:30:41.123456789Z"), line.time());
        assertEquals("GET /health 200", line.message());
    }

    @Test
    void 시각이_없는_줄은_시각이_null이고_내용은_그대로다() {
        LogLine line = LogLine.parse("    at com.example.Main.run(Main.java:42)");

        assertNull(line.time());
        assertEquals("    at com.example.Main.run(Main.java:42)", line.message());
    }

    @Test
    void 날짜_모양이_아닌_첫_낱말은_시각으로_보지_않는다() {
        LogLine line = LogLine.parse("Listening on port 8080");

        assertNull(line.time());
        assertEquals("Listening on port 8080", line.message());
    }

    @Test
    void 오류_단어가_있으면_오류다() {
        assertEquals(Level.ERROR, level("[error] 12#12: upstream prematurely closed connection"));
        assertEquals(Level.ERROR, level("FATAL: database is down"));
        assertEquals(Level.ERROR, level("panic: runtime error: index out of range"));
        assertEquals(Level.ERROR, level("level=error msg=\"dial failed\""));
        assertEquals(Level.ERROR, level("Traceback (most recent call last):"));
    }

    @Test
    void 경고_단어가_있으면_경고다() {
        assertEquals(Level.WARN, level("WARN cache miss ratio high"));
        assertEquals(Level.WARN, level("[warning] deprecated option"));
        assertEquals(Level.WARN, level("level=warn msg=\"slow query\""));
    }

    @Test
    void 접속_기록은_상태_코드로_나눈다() {
        assertEquals(Level.INFO, level("\"GET /health HTTP/1.1\" 200 2 \"-\" \"uptime-check\""));
        assertEquals(Level.WARN, level("\"GET /favicon.ico HTTP/1.1\" 404 153 \"-\" \"Mozilla/5.0\""));
        assertEquals(Level.ERROR, level("\"POST /api/webhook HTTP/1.1\" 502 157 \"-\" \"Discord-Webhook\""));
    }

    @Test
    void 오류_규칙이_경고_규칙보다_먼저다() {
        assertEquals(Level.ERROR, level("warning: error while reading config"));
    }

    @Test
    void 단어_단위로만_맞춘다() {
        // errors, terror 같은 낱말 속의 글자는 해당하지 않는다.
        assertEquals(Level.INFO, level("job finished with 0 errors"));
        assertEquals(Level.INFO, level("terror of the deep"));
        assertEquals(Level.INFO, level("Server started"));
    }

    @Test
    void 대소문자는_구분하지_않는다() {
        assertEquals(Level.ERROR, level("Error: connection refused"));
        assertEquals(Level.ERROR, level("ERROR: connection refused"));
    }

    @Test
    void 로그_전체를_줄로_나누고_빈_줄은_건너뛴다() {
        String output = "2025-10-27T06:30:41Z first\n\n2025-10-27T06:30:42Z [error] second\r\n   continued\n";

        List<LogLine> lines = LogLine.parseAll(output);

        assertEquals(3, lines.size());
        assertEquals("first", lines.get(0).message());
        assertEquals(Level.ERROR, lines.get(1).level());
        assertNull(lines.get(2).time());
    }

    @Test
    void 수준에는_화면에_보이는_이름이_있다() {
        assertEquals("정보", Level.INFO.label());
        assertEquals("경고", Level.WARN.label());
        assertEquals("오류", Level.ERROR.label());
    }
}
