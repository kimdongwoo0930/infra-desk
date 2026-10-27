package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditBannerTest {

    @Test
    void textNamesTheFileAndSaysSavingUploadsIt() {
        String text = EditBanner.plainText(1, "/etc/app/config.conf", null);

        assertEquals("편집 중 1개 · /etc/app/config.conf — 편집기에서 저장하면 서버에 자동으로 올려요", text);
    }

    @Test
    void textCountsTheOtherFilesWhenSeveralAreOpen() {
        assertTrue(EditBanner.plainText(3, "/a/b.txt", null).startsWith("편집 중 3개 · /a/b.txt 외 2개 — "));
    }

    @Test
    void lastUploadTimeIsShownOnlyAfterSomethingWasUploaded() {
        assertEquals("", EditBanner.uploadedAt(null));
        assertTrue(EditBanner.uploadedAt(Instant.parse("2026-10-23T06:28:00Z")).startsWith(" · 마지막 업로드 "));
        assertTrue(EditBanner.plainText(1, "/a", Instant.EPOCH).contains("마지막 업로드"));
    }

    @Test
    void pathSpecialCharactersAreEscapedSoTheyShowAsText() {
        assertEquals("/a/&lt;b&gt;&amp;c", EditBanner.escape("/a/<b>&c"));
        assertFalse(EditBanner.escape("<html>").contains("<"));
    }
}
