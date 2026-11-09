package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;

import javax.swing.JOptionPane;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloseChoiceTest {

    @Test
    void 버튼_순서는_저장_저장_안_함_취소다() {
        assertEquals(CloseChoice.SAVE, CloseChoice.fromOption(0));
        assertEquals(CloseChoice.DISCARD, CloseChoice.fromOption(1));
        assertEquals(CloseChoice.CANCEL, CloseChoice.fromOption(2));
        assertEquals("저장", CloseChoice.BUTTONS[0]);
        assertEquals("저장 안 함", CloseChoice.BUTTONS[1]);
        assertEquals("취소", CloseChoice.BUTTONS[2]);
    }

    @Test
    void 확인_창을_X로_닫으면_저장_안_하고_닫는다() {
        assertEquals(CloseChoice.DISCARD, CloseChoice.fromOption(JOptionPane.CLOSED_OPTION));
    }
}
