package com.infradesk.ui.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 파일이 편집기에서 저장됐는지 알아본다. 주기적으로 {@link #poll()}을 부르면, 파일이 바뀐 뒤 한 번 더
 * 확인했을 때도 같은 상태일 때(저장이 끝났을 때)만 true를 준다. 편집기가 저장하는 도중의 파일을
 * 서버에 올리지 않으려는 것이다. 처리한 뒤에는 {@link #acknowledge()}를 부른다.
 */
final class ChangeWatch {

    private record Stamp(long size, long modifiedMillis) {
    }

    private final Path file;
    private Stamp base;
    private Stamp seen;

    ChangeWatch(Path file) throws IOException {
        this.file = file;
        this.base = read();
    }

    /** @return 바뀐 파일이 안정됐으면 true. {@link #acknowledge()}를 부르기 전까지 계속 true */
    boolean poll() {
        Stamp now = readOrNull();
        if (now == null) {
            // 편집기가 파일을 바꿔치기하는 도중에 잠깐 없을 수 있다.
            return false;
        }
        if (now.equals(base)) {
            seen = null;
            return false;
        }
        if (Objects.equals(now, seen)) {
            return true;
        }
        seen = now;
        return false;
    }

    /** 지금 파일 상태를 처리했다고 알린다. 이 상태와 다르게 또 바뀌면 다시 감지한다. */
    void acknowledge() {
        Stamp now = readOrNull();
        base = now != null ? now : base;
        seen = null;
    }

    /** 마지막으로 처리한 뒤로 바뀐 부분이 있는지(아직 서버에 못 올렸을 수 있다). */
    boolean dirty() {
        Stamp now = readOrNull();
        return now != null && !now.equals(base);
    }

    private Stamp read() throws IOException {
        return new Stamp(Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    private Stamp readOrNull() {
        try {
            return read();
        } catch (IOException e) {
            return null;
        }
    }
}
