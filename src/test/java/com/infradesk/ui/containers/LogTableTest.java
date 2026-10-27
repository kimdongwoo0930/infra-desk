package com.infradesk.ui.containers;

import com.infradesk.ssh.LogLine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTableTest {

    private static List<LogLine> lines() {
        return LogLine.parseAll(String.join("\n",
                "2025-10-27T06:30:41Z GET /health 200",
                "2025-10-27T06:30:42Z [error] upstream closed"));
    }

    @Test
    void 복사에_쓰이는_값은_화면에_보이는_글자다() {
        LogTable table = new LogTable();
        table.setLines(lines());

        assertEquals(2, table.lineCount());
        // 시각은 사용 중인 시간대로 나오므로 형식(HH:mm:ss)만 확인한다.
        assertTrue(table.getValueAt(0, 0).toString().matches("\\d\\d:\\d\\d:\\d\\d"));
        assertEquals("정보", table.getValueAt(0, 1));
        assertEquals("오류", table.getValueAt(1, 1));
        assertEquals("[error] upstream closed", table.getValueAt(1, 2));
    }

    @Test
    void 줄바꿈을_켜면_긴_줄의_높이가_늘고_끄면_한_줄_높이로_돌아온다() {
        LogTable table = new LogTable();
        String longText = "word ".repeat(80);
        table.setLines(LogLine.parseAll("2025-10-27T06:30:41Z " + longText));
        int oneLine = table.getRowHeight(0);

        table.setWrap(true);
        table.setBounds(0, 0, 320, 400);
        table.doLayout();
        assertTrue(table.getRowHeight(0) > oneLine, "긴 줄은 여러 줄 높이가 되어야 한다");

        table.setWrap(false);
        assertEquals(oneLine, table.getRowHeight(0));
    }

    @Test
    void 오류_줄만_붉은_배경이다() {
        LogTable table = new LogTable();
        table.setLines(lines());
        table.setBounds(0, 0, 400, 100);

        // 표는 셀마다 같은 라벨 객체를 되돌려 쓴다. 그래서 두 개를 한꺼번에 받아 두면 마지막 셀의 모양이 둘 다에
        // 보인다. 호출 직후에 색을 읽어야 한다.
        java.awt.Color infoBg = table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2).getBackground();
        java.awt.Color errorBg = table.prepareRenderer(table.getCellRenderer(1, 2), 1, 2).getBackground();

        assertEquals(com.infradesk.ui.Theme.TERMINAL_BG, infoBg);
        assertEquals(LogTable.ERROR_ROW_BG, errorBg);
    }
}
