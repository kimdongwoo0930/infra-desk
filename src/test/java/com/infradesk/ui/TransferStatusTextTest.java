package com.infradesk.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferStatusTextTest {

    @Test
    void statusBarShowsCountAndPercent() {
        assertEquals("전송 1개 진행 중 · 63%", MainFrame.transferStatusText(1, 63));
    }

    @Test
    void statusBarOmitsPercentWhileUnknown() {
        assertEquals("전송 2개 진행 중", MainFrame.transferStatusText(2, -1));
    }
}
