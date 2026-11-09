package com.infradesk.ui.files;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** 내 컴퓨터 안에서 파일·폴더를 복사한다(양쪽 다 내 컴퓨터 패널일 때). 화면 스레드에서 부르지 않는다. */
final class LocalCopy {

    private LocalCopy() {
    }

    /**
     * {@code source}를 {@code targetDir} 안에 {@code newName}으로 복사한다. 폴더는 안의 것까지 복사하고,
     * 이미 있는 폴더에는 합치며 같은 이름의 파일만 덮어쓴다. 심볼릭 링크는 따라가지 않고 링크 그대로 복사한다.
     *
     * @throws IOException 폴더를 자기 자신 안에 복사하려 하거나 복사에 실패했을 때
     */
    static void copy(Path source, Path targetDir, String newName) throws IOException {
        Path target = targetDir.resolve(newName);
        Path from = source.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        if (from.equals(to)) {
            throw new IOException("같은 위치로는 복사할 수 없어요: " + source.getFileName());
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && to.startsWith(from)) {
            throw new IOException("폴더를 자기 자신 안으로는 복사할 수 없어요: " + source.getFileName());
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES,
                    StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
