package com.infradesk.ssh;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 이 컴퓨터의 폴더 탐색. SFTP 창의 왼쪽 패널이 서버 쪽({@link RemoteFiles})과 같은 표를 쓰도록
 * 항목을 {@link RemoteFile} 모양으로 돌려준다. 경로는 OS의 경로 표기를 그대로 쓴다
 * (macOS {@code /Users/me}, Windows {@code C:\Users\me}). 블로킹이므로 EDT 밖에서 호출한다.
 *
 * <p>{@code root}가 있으면 그 밖으로는 볼 수 없다(데모 모드가 실제 폴더를 보여주지 않게 하려는 것).
 */
public final class LocalFiles {

    private final Path home;
    private final Path root;

    /** 사용자의 홈 폴더에서 시작한다. 제한 없음. */
    public static LocalFiles userHome() {
        return new LocalFiles(Path.of(System.getProperty("user.home")), null);
    }

    /** {@code root} 아래만 볼 수 있다. {@code home}은 그 안에 있어야 한다. */
    public LocalFiles(Path home, Path root) {
        this.home = home.toAbsolutePath().normalize();
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    public String home() {
        return home.toString();
    }

    /** 폴더 항목. 폴더가 먼저, 그다음 이름순(대소문자 무시). 읽을 수 없는 항목은 건너뛴다. */
    public List<RemoteFile> list(String directory) throws IOException {
        Path dir = resolve(directory);
        List<RemoteFile> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    Instant modified = a.lastModifiedTime().toInstant();
                    result.add(new RemoteFile(p.getFileName().toString(), p.toString(), a.isDirectory(),
                            a.isDirectory() ? 0 : a.size(), modified));
                } catch (IOException | SecurityException skipped) {
                    // 끊어진 링크나 권한 없는 항목은 목록에서 뺀다.
                }
            }
        }
        result.sort(Comparator.comparing((RemoteFile f) -> !f.directory())
                .thenComparing(RemoteFile::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public boolean exists(String path) {
        return Files.exists(Path.of(path));
    }

    public String join(String directory, String name) {
        return Path.of(directory).resolve(name).toString();
    }

    /** 상위 폴더. 더 올라갈 수 없으면 자기 자신. */
    public String parent(String path) {
        Path p = Path.of(path).toAbsolutePath().normalize();
        Path parent = p.getParent();
        if (parent == null || (root != null && !parent.startsWith(root))) {
            return p.toString();
        }
        return parent.toString();
    }

    /** 더 올라갈 수 없는 폴더인지(파일 시스템 루트, 또는 제한된 루트). */
    public boolean isRoot(String path) {
        return parent(path).equals(Path.of(path).toAbsolutePath().normalize().toString());
    }

    private Path resolve(String directory) throws IOException {
        Path p = Path.of(directory).toAbsolutePath().normalize();
        if (root != null && !p.startsWith(root)) {
            throw new IOException("이 폴더는 볼 수 없어요: " + directory);
        }
        if (!Files.isDirectory(p)) {
            throw new IOException("폴더가 아니에요: " + directory);
        }
        return p;
    }
}
