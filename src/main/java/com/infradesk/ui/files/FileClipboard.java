package com.infradesk.ui.files;

import com.infradesk.ssh.RemoteFile;

import java.util.List;

/**
 * 파일 탭의 복사해 둔 항목. 앱 전체에서 하나라서, 다른 서버의 파일 탭에서도 붙여넣을 수 있다.
 * 시스템 클립보드(Finder·탐색기)와는 따로다. 항목은 복사한 시점의 목록이라, 그사이 지워졌다면 붙여넣을 때
 * 전송이 실패로 표시된다. 화면 스레드에서만 쓴다.
 */
final class FileClipboard {

    /**
     * 복사한 것.
     *
     * @param owner  복사한 파일 탭(서버 쪽 항목은 이 탭의 연결로 읽는다)
     * @param remote 서버 패널에서 복사했나(아니면 내 컴퓨터 패널)
     * @param dir    복사한 폴더(같은 폴더에 붙여넣는지 가려 "복사" 이름을 붙이는 데 쓴다)
     * @param items  복사한 항목
     */
    record Entry(FilesTab owner, boolean remote, String dir, List<RemoteFile> items) {
        Entry {
            items = List.copyOf(items);
        }
    }

    private static Entry current;
    /** 클립보드가 바뀔 때 불리는 쪽(파일 탭의 [붙여넣기] 버튼을 켜고 끈다). */
    private static final List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private FileClipboard() {
    }

    static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void changed() {
        for (Runnable l : LISTENERS) {
            l.run();
        }
    }

    static void set(Entry entry) {
        current = entry;
        changed();
    }

    /** 복사해 둔 것. 없으면 null. */
    static Entry get() {
        return current;
    }

    static boolean isEmpty() {
        return current == null || current.items().isEmpty();
    }

    static void clear() {
        current = null;
        changed();
    }

    /** 이 탭이 닫히면(서버 삭제, 앱 종료) 그 탭에서 복사한 서버 항목은 더 읽을 수 없으니 비운다. */
    static void clearIfOwnedBy(FilesTab tab) {
        if (current != null && current.owner() == tab && current.remote()) {
            current = null;
            changed();
        }
    }
}
