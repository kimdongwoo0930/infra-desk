package com.infradesk.demo;

import com.infradesk.ssh.LocalFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 데모 모드용 "내 컴퓨터". 실제 홈 폴더 대신 가짜 파일이 든 임시 폴더만 보여주고, 그 밖으로는
 * 나갈 수 없다. 앱이 끝나면 지워진다.
 */
public final class DemoLocalFiles {

    private static LocalFiles shared;

    private DemoLocalFiles() {
    }

    /** 앱 실행 중에는 같은 폴더를 쓴다(창을 닫았다 열어도 받은 파일이 남아 있게). */
    public static synchronized LocalFiles get() {
        if (shared == null) {
            shared = create();
        }
        return shared;
    }

    private static LocalFiles create() {
        try {
            Path root = Files.createTempDirectory("infradesk-demo-");
            Path home = Files.createDirectories(root.resolve("me"));
            Files.createDirectories(home.resolve("Documents"));
            Files.createDirectories(home.resolve("Downloads"));
            Files.createDirectories(home.resolve("Projects"));
            write(home.resolve("notes.txt"), "데모용 파일이에요. 실제 내 컴퓨터의 파일이 아니에요.\n");
            write(home.resolve("deploy.sh"), "#!/bin/sh\ndocker compose pull && docker compose up -d\n");
            write(home.resolve("Documents/report.md"), "# 주간 보고\n\n- 서버 점검 완료\n");
            write(home.resolve("Downloads/backup.tar.gz"), "x".repeat(120_000));
            write(home.resolve("Projects/config.json"), "{\n  \"env\": \"demo\"\n}\n");
            deleteOnExit(root);
            return new LocalFiles(home, root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteOnExit(Path root) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // 임시 폴더라 못 지워도 괜찮다.
            }
        }));
    }
}
