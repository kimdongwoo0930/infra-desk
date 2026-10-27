package com.infradesk.ui.terminal;

import com.infradesk.ssh.ExecResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchResultViewTest {

    @Test
    void summaryListsOnlyTheKindsThatExist() {
        assertTrue(BatchResultView.summaryHtml(2, 1, 0).contains("2 성공"));
        assertTrue(BatchResultView.summaryHtml(2, 1, 0).contains("1 실패"));
        assertFalse(BatchResultView.summaryHtml(3, 0, 0).contains("실패"));
        assertFalse(BatchResultView.summaryHtml(0, 2, 0).contains("성공"));
        assertTrue(BatchResultView.summaryHtml(1, 0, 2).contains("2 실행 중"));
        assertEquals("", BatchResultView.summaryHtml(0, 0, 0));
    }

    @Test
    void durationReadsInSecondsThenMinutes() {
        assertEquals("0.3초", BatchResultView.durationText(300));
        assertEquals("0.0초", BatchResultView.durationText(-5), "음수는 0으로");
        assertEquals("9.9초", BatchResultView.durationText(9_940));
        assertEquals("10초", BatchResultView.durationText(10_200));
        assertEquals("59초", BatchResultView.durationText(59_000));
        assertEquals("1분", BatchResultView.durationText(60_000));
        assertEquals("1분 5초", BatchResultView.durationText(65_000));
    }

    @Test
    void previewIsTheFirstNonBlankOutputLine() {
        assertEquals("15:31:40 up 9 days", BatchResultView.previewLine(new ExecResult(0, "\n  15:31:40 up 9 days  \nsecond\n", null, false)));
        assertEquals("(출력 없음)", BatchResultView.previewLine(new ExecResult(0, "\n \n", null, false)));
    }

    @Test
    void previewOfAFailureIsTheErrorText() {
        assertEquals("시간 초과", BatchResultView.previewLine(ExecResult.failure("시간 초과")));
    }

    @Test
    void fullTextAppendsTruncationAndErrorNotes() {
        assertEquals("(출력 없음)", BatchResultView.fullText(new ExecResult(0, "", null, false)));
        String truncated = BatchResultView.fullText(new ExecResult(0, "abc", null, true));
        assertTrue(truncated.startsWith("abc") && truncated.contains("생략"));
        String failed = BatchResultView.fullText(new ExecResult(1, "partial", "연결이 끊겼어요", false));
        assertTrue(failed.contains("partial") && failed.contains("⚠ 연결이 끊겼어요"));
    }

    @Test
    void aNonZeroExitCodeIsAFailureEvenWithoutAnError() {
        assertTrue(BatchResultView.succeeded(new ExecResult(0, "", null, false)));
        assertFalse(BatchResultView.succeeded(new ExecResult(2, "", null, false)));
        assertFalse(BatchResultView.succeeded(ExecResult.failure("x")));
    }
}
