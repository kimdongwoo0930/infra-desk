package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;

import com.infradesk.ssh.RemoteFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePaneTest {

    @Test
    void recognizesPermissionErrorsFromTheServerAndTheOs() {
        assertTrue(FilePane.isPermissionError(new IOException("SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied")));
        assertTrue(FilePane.isPermissionError(new java.nio.file.AccessDeniedException("/x")));
        assertTrue(FilePane.isPermissionError(new java.nio.file.FileSystemException("/Users/a/Desktop: Operation not permitted")));
        assertTrue(FilePane.isPermissionError(new IOException("감싼 오류", new java.nio.file.AccessDeniedException("/x"))), "원인 체인도 본다");
        assertFalse(FilePane.isPermissionError(new IOException("연결이 끊겼어요")));
    }

    @Test
    void hintsAtMacPrivacyProtectionOnlyForProtectedFolders() {
        for (String folder : new String[] {"Desktop", "Documents", "Downloads", "Movies", "Music", "Pictures", "Library"}) {
            assertNotNull(FilePane.permissionHint("/Users/me/" + folder, true), folder);
        }
        assertNotNull(FilePane.permissionHint("/Users/me/Downloads/project/src", true), "하위 폴더도 같은 제한");

        assertNull(FilePane.permissionHint("/Users/me", true), "홈 폴더 자체는 해당 없음");
        assertNull(FilePane.permissionHint("/Users/me/work/Downloads", true), "홈 바로 아래가 아니면 해당 없음");
        assertNull(FilePane.permissionHint("/etc", true));
        assertNull(FilePane.permissionHint("/home/ubuntu/Desktop", true), "Linux 경로에는 안내하지 않는다");
        assertNull(FilePane.permissionHint("C:\\Users\\me\\Desktop", false));
        assertNull(FilePane.permissionHint(null, true));
    }

    @Test
    void remoteAndLocalHintsDiffer() {
        String remote = FilePane.permissionHint("/Users/me/Desktop", true);
        String local = FilePane.permissionHint("/Users/me/Desktop", false);

        assertTrue(remote.contains("원격 로그인"), remote);
        assertTrue(local.contains("개인정보 보호"), local);
    }

    @Test
    void friendlyMessagesForCommonErrors() {
        assertTrue(FilePane.friendly(new IOException("SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied")).contains("권한이 없어요"));
        assertTrue(FilePane.friendly(new java.nio.file.NoSuchFileException("/x")).contains("없어요"));
        assertTrue(FilePane.friendly(new java.nio.file.FileAlreadyExistsException("/x")).contains("이미 있어요"));
        assertTrue(FilePane.friendly(new java.nio.file.FileSystemException("/Users/a/Desktop: Operation not permitted"))
                .contains("권한이 없어요"));
    }

    @Test
    void summaryLineShowsCountsAndAnOptionalNote() {
        assertEquals("폴더 1개 · 파일 3개", FilePane.summaryText(1, 3, ""));
        assertEquals("폴더 1개 · 파일 3개", FilePane.summaryText(1, 3, null));
        assertEquals("폴더 3개 · 파일 3개 · ubuntu 권한으로 접속 중", FilePane.summaryText(3, 3, "ubuntu 권한으로 접속 중"));
    }

    @Test
    void hidingHiddenFilesDropsOnlyNamesStartingWithADot() {
        RemoteFile env = new RemoteFile(".env", "/h/.env", false, 412, Instant.EPOCH);
        RemoteFile app = new RemoteFile("app", "/h/app", true, 0, Instant.EPOCH);
        RemoteFile dotDir = new RemoteFile(".cache", "/h/.cache", true, 0, Instant.EPOCH);
        List<RemoteFile> all = List.of(env, app, dotDir);

        assertEquals(all, FilePane.visible(all, true), "켜 두면 전부 보인다");
        assertEquals(List.of(app), FilePane.visible(all, false));
    }

    @Test
    void onMacAnyPathOfThisComputerGetsThePermissionHint() {
        assertNotNull(FilePane.hintFor("/", false, true));
        assertNotNull(FilePane.hintFor("/Users/other", false, true), "다른 사용자의 홈 같은 곳도 막힌다");
        assertNotNull(FilePane.hintFor("/Library/Application Support", false, true));
    }

    @Test
    void outsideMacOnlyKnownProtectedFoldersGetAHintAndRemoteIsUnchanged() {
        assertNull(FilePane.hintFor("/home/me/x", false, false), "Mac이 아니면 일반 경로에는 안내하지 않는다");
        assertNull(FilePane.hintFor("/", true, true), "서버 쪽은 알려진 보호 폴더만(서버가 Mac일 때)");
        assertNotNull(FilePane.hintFor("/Users/me/Desktop", true, true));
    }

    @Test
    void theLocalHintPointsToTheSettingsButtonAndToTheLauncherDuringDevelopment() {
        String hint = FilePane.hintFor("/", false, true);

        assertTrue(hint.contains("권한 설정 열기"));
        assertTrue(hint.contains("전체 디스크 접근"));
        assertTrue(hint.contains("IntelliJ"), "개발 중에는 앱을 띄운 프로그램이 허용 대상이다");
    }
}
