package com.infradesk.ui.files;

import com.infradesk.ssh.TransferQueue.Direction;
import com.infradesk.ssh.TransferQueue.State;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferPanelTest {

    @Test
    void headerSummaryCountsRunningAndFinished() {
        assertEquals("1개 진행 중 · 2개 끝남", TransferPanel.summaryText(1, 2));
        assertEquals("3개 진행 중", TransferPanel.summaryText(3, 0));
        assertEquals("2개 끝남", TransferPanel.summaryText(0, 2));
        assertEquals(" ", TransferPanel.summaryText(0, 0));
    }

    @Test
    void rowButtonDependsOnState() {
        assertEquals(TransferPanel.RowAction.CANCEL, TransferPanel.actionFor(State.QUEUED));
        assertEquals(TransferPanel.RowAction.CANCEL, TransferPanel.actionFor(State.RUNNING));
        assertEquals(TransferPanel.RowAction.RETRY, TransferPanel.actionFor(State.FAILED));
        assertEquals(TransferPanel.RowAction.RETRY, TransferPanel.actionFor(State.CANCELLED));
        assertNull(TransferPanel.actionFor(State.DONE), "끝난 행에는 버튼이 없다");
    }

    @Test
    void rowButtonSitsAtTheRightEdgeAndIsVerticallyCentered() {
        Rectangle box = TransferPanel.buttonBounds(200, 52);

        assertEquals(52 / 2, box.y + box.height / 2);
        assertTrue(box.x + box.width < 200, "오른쪽 여백을 둔다");
        assertTrue(box.contains(box.x + 1, 26));
        assertTrue(!box.contains(10, 26), "버튼 밖은 눌리지 않는다");
    }

    @Test
    void detailLineShowsRouteAmountAndSpeedWhileRunning() {
        assertEquals("서버 → ~/Downloads · 30.4 MB / 48.2 MB · 12.4 MB/s",
                TransferPanel.detailText(Direction.DOWNLOAD, "/home/u/db.sql.gz", "~/Downloads",
                        (long) (30.4 * 1024 * 1024), (long) (48.2 * 1024 * 1024), 12.4 * 1024 * 1024, true));
    }

    @Test
    void detailLineOfAnUploadStartsFromTheLocalFolder() {
        assertEquals("/Users/me/Downloads → /home/ubuntu · 1.2 KB",
                TransferPanel.detailText(Direction.UPLOAD, "/Users/me/Downloads/docker-compose.yml", "/home/ubuntu",
                        1229, 1229, 0, false));
    }

    @Test
    void detailLineHasNoSizeWhileTheFileListIsStillBeingRead() {
        assertEquals("서버 → /tmp", TransferPanel.detailText(Direction.DOWNLOAD, "/x", "/tmp", 0, -1, 0, true));
    }

    @Test
    void statusTextReadsPerState() {
        assertEquals("63% · 2초 남음", TransferPanel.statusText(State.RUNNING, "", 63, "2초 남음", true));
        assertEquals("63%", TransferPanel.statusText(State.RUNNING, "", 63, "", true), "속도를 모르면 남은 시간은 뺀다");
        assertEquals("파일 목록을 읽는 중…", TransferPanel.statusText(State.RUNNING, "", 0, "", false));
        assertEquals("대기 중", TransferPanel.statusText(State.QUEUED, "", 0, "", true));
        assertEquals("끝남", TransferPanel.statusText(State.DONE, "완료", 100, "", true));
        assertEquals("끝남 · 덮어씀, 권한 유지", TransferPanel.statusText(State.DONE, "완료 · 덮어씀, 권한 유지", 100, "", true));
        assertEquals("실패 · 서버에서 읽을 권한이 없어요",
                TransferPanel.statusText(State.FAILED, "서버에서 읽을 권한이 없어요", 10, "", true));
        assertEquals("취소했어요", TransferPanel.statusText(State.CANCELLED, "취소했어요", 10, "", true));
    }

    @Test
    void homeFolderIsShownAsTilde() {
        String sep = java.io.File.separator;
        String home = sep + "Users" + sep + "me";
        assertEquals("~", TransferPanel.tilde(home, home));
        assertEquals("~/Downloads", TransferPanel.tilde(home + sep + "Downloads", home));
        assertEquals(sep + "Users" + sep + "meow" + sep + "x", TransferPanel.tilde(sep + "Users" + sep + "meow" + sep + "x", home),
                "이름이 비슷한 다른 폴더는 줄이지 않는다");
        assertEquals("/home/ubuntu", TransferPanel.tilde("/home/ubuntu", ""), "홈을 모르면 그대로");
    }
}
