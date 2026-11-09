package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCopyTest {

    @TempDir
    Path dir;

    @Test
    void 파일을_다른_이름으로_복사한다() throws IOException {
        Path source = Files.writeString(dir.resolve("a.txt"), "내용");

        LocalCopy.copy(source, dir, "a 복사.txt");

        assertEquals("내용", Files.readString(dir.resolve("a 복사.txt")));
        assertTrue(Files.exists(source), "원본은 그대로");
    }

    @Test
    void 폴더는_안의_것까지_복사한다() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/inner"));
        Files.writeString(src.resolve("x.txt"), "x");
        Files.writeString(dir.resolve("src/top.txt"), "top");
        Path other = Files.createDirectory(dir.resolve("other"));

        LocalCopy.copy(dir.resolve("src"), other, "src");

        assertEquals("x", Files.readString(other.resolve("src/inner/x.txt")));
        assertEquals("top", Files.readString(other.resolve("src/top.txt")));
    }

    @Test
    void 이미_있는_폴더에는_합치고_같은_이름의_파일만_덮어쓴다() throws IOException {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/new.txt"), "새것");
        Files.writeString(dir.resolve("src/same.txt"), "덮어씀");
        Path other = Files.createDirectories(dir.resolve("other/src"));
        Files.writeString(other.resolve("same.txt"), "옛것");
        Files.writeString(other.resolve("keep.txt"), "그대로");

        LocalCopy.copy(dir.resolve("src"), dir.resolve("other"), "src");

        assertEquals("덮어씀", Files.readString(other.resolve("same.txt")));
        assertEquals("그대로", Files.readString(other.resolve("keep.txt")));
        assertEquals("새것", Files.readString(other.resolve("new.txt")));
    }

    @Test
    void 폴더를_자기_안으로_복사하면_막는다() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));

        assertThrows(IOException.class, () -> LocalCopy.copy(src, src, "inner"));
        assertFalse(Files.exists(src.resolve("inner")));
    }

    @Test
    void 같은_위치_같은_이름으로는_복사하지_않는다() throws IOException {
        Path source = Files.writeString(dir.resolve("a.txt"), "내용");

        assertThrows(IOException.class, () -> LocalCopy.copy(source, dir, "a.txt"));
        assertEquals("내용", Files.readString(source), "원본이 망가지면 안 된다");
    }
}
