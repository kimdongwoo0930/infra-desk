package com.infradesk.ui;

import com.infradesk.core.AccessMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 타이틀바의 읽기 | 관리 토글. */
class TitleBarModeTest {

    @Test
    void theToggleStartsOnReadAndHasBothChoices() {
        TitleBar bar = new TitleBar(false);

        assertEquals(AccessMode.READ_ONLY.ordinal(), bar.modeSwitch().getSelectedIndex());
        assertTrue(SwingTree.buttons(bar).containsAll(List.of("읽기", "관리")));
    }

    @Test
    void pressingManageAsksTheListenerForThatMode() {
        TitleBar bar = new TitleBar(false);
        List<AccessMode> picked = new ArrayList<>();
        bar.onModePicked(picked::add);

        bar.modeSwitch().setSelectedIndex(AccessMode.MANAGE.ordinal());

        assertEquals(List.of(AccessMode.MANAGE), picked);
    }

    @Test
    void settingTheModeFromCodeDoesNotCountAsThePersonPressingIt() {
        TitleBar bar = new TitleBar(false);
        List<AccessMode> picked = new ArrayList<>();
        bar.onModePicked(picked::add);

        bar.setMode(AccessMode.MANAGE);

        assertEquals(AccessMode.MANAGE.ordinal(), bar.modeSwitch().getSelectedIndex());
        assertTrue(picked.isEmpty(), "비밀번호를 확인하지 못해 되돌릴 때 다시 묻는 일이 없어야 한다");
    }
}
