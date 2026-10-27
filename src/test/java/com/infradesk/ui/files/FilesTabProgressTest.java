package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilesTabProgressTest {

    @Test
    void percentIsWholeNumberOfOverallProgress() {
        assertEquals(63, new FilesTab.Progress(1, 63, 100).percent());
        assertEquals(50, new FilesTab.Progress(2, 300, 600).percent());
    }

    @Test
    void percentIsUnknownBeforeAnySizeIsKnown() {
        assertEquals(-1, new FilesTab.Progress(1, 0, 0).percent());
    }

    @Test
    void percentNeverExceedsOneHundred() {
        assertEquals(100, new FilesTab.Progress(1, 150, 100).percent());
    }
}
