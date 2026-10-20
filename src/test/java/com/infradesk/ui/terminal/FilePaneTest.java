package com.infradesk.ui.terminal;

import org.junit.jupiter.api.Test;

import java.io.IOException;

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
}
