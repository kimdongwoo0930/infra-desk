package com.infradesk.ssh;

import java.time.Instant;

/**
 * 폴더 항목 하나(서버 또는 이 컴퓨터).
 *
 * @param path        절대 경로
 * @param modified    마지막 수정 시각. 없으면 null
 * @param permissions POSIX 권한 비트(0777 범위). 알 수 없으면 -1
 * @param symlink     심볼릭 링크인지. 링크는 따라가지 않고 링크 자체로 다룬다(재귀 삭제·전송이
 *                    링크가 가리키는 다른 곳의 파일을 건드리지 않게)
 */
public record RemoteFile(String name, String path, boolean directory, long size, Instant modified,
                         int permissions, boolean symlink) {

    public static final int UNKNOWN_PERMISSIONS = -1;

    public RemoteFile(String name, String path, boolean directory, long size, Instant modified) {
        this(name, path, directory, size, modified, UNKNOWN_PERMISSIONS, false);
    }

    /** {@code rwxr-xr-x} 형태. 권한을 모르면 빈 문자열. */
    public String permissionText() {
        if (permissions < 0) {
            return "";
        }
        char[] out = new char[9];
        String rwx = "rwx";
        for (int i = 0; i < 9; i++) {
            boolean set = (permissions & (1 << (8 - i))) != 0;
            out[i] = set ? rwx.charAt(i % 3) : '-';
        }
        return new String(out);
    }
}
