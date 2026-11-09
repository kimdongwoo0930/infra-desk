package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopyNamesTest {

    @Test
    void 확장자_앞에_복사를_붙인다() {
        assertEquals("report 복사.txt", CopyNames.unique("report.txt", false, Set.of("report.txt")));
    }

    @Test
    void 이미_있으면_번호를_붙인다() {
        assertEquals("report 복사 2.txt", CopyNames.unique("report.txt", false, Set.of("report.txt", "report 복사.txt")));
        assertEquals("report 복사 3.txt",
                CopyNames.unique("report.txt", false, Set.of("report.txt", "report 복사.txt", "report 복사 2.txt")));
    }

    @Test
    void 확장자가_없거나_폴더면_뒤에_붙인다() {
        assertEquals("data 복사", CopyNames.unique("data", false, Set.of("data")));
        assertEquals("v1.2 복사", CopyNames.unique("v1.2", true, Set.of("v1.2")), "폴더 이름의 점은 확장자가 아니다");
    }

    @Test
    void 숨김_파일의_맨_앞_점은_확장자가_아니다() {
        assertEquals(".bashrc 복사", CopyNames.unique(".bashrc", false, Set.of(".bashrc")));
    }

    @Test
    void 여러_점이_있으면_마지막_점부터_확장자다() {
        assertEquals("backup.tar 복사.gz", CopyNames.unique("backup.tar.gz", false, Set.of("backup.tar.gz")));
    }
}
