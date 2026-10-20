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
                            a.isDirectory() ? 0 : a.size(), modified, RemoteFile.UNKNOWN_PERMISSIONS,
                            Files.isSymbolicLink(p)));
                } catch (IOException | SecurityException skipped) {
                    // 끊어진 링크나 권한 없는 항목은 목록에서 뺀다.
                }
            }
        }
        result.sort(Comparator.comparing((RemoteFile f) -> !f.directory())
                .thenComparing(RemoteFile::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** 새 폴더. 이미 있거나 상위 폴더가 없으면 실패한다. */
    public void mkdir(String path) throws IOException {
        Files.createDirectory(inside(path));
    }

    /** 빈 파일. 이미 있으면 덮어쓰지 않고 실패한다. */
    public void createFile(String path) throws IOException {
        Files.createFile(inside(path));
    }

    /** 이름 바꾸기. 대상이 이미 있으면 덮어쓰지 않고 실패한다. */
    public void rename(String from, String to) throws IOException {
        Files.move(inside(from), inside(to));
    }

    /**
     * 파일이나 폴더(안의 모든 것)를 지운다. 이 컴퓨터의 일반 모드에서는 휴지통으로 보내서
     * 실수해도 되돌릴 수 있게 하고, 휴지통을 쓸 수 없을 때만 영구 삭제한다. 심볼릭 링크는 따라가지
     * 않고 링크만 지운다. 데모처럼 {@code root}가 제한된 경우는 항상 영구 삭제한다(실제 휴지통을
     * 건드리지 않게).
     *
     * @return 휴지통으로 보냈으면 true, 영구 삭제했으면 false
     */
    public boolean delete(String path) throws IOException {
        Path p = inside(path);
        if (root == null && !Files.isSymbolicLink(p) && moveToTrash(p)) {
            return true;
        }
        deleteTree(p);
        return false;
    }

    /** {@link #delete}가 휴지통을 쓰는지(되돌릴 수 있는지). 확인 창 문구를 정하는 데 쓴다. */
    public boolean movesToTrash() {
        try {
            return root == null && java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static boolean moveToTrash(Path p) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                return java.awt.Desktop.getDesktop().moveToTrash(p.toFile());
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            // 휴지통을 쓸 수 없는 환경이다. 영구 삭제로 넘어간다.
        }
        return false;
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(p)) {
                for (Path child : entries) {
                    deleteTree(child);
                }
            }
        }
        Files.delete(p);
    }

    /** 제한된 루트 밖의 경로를 막는다. */
    private Path inside(String path) throws IOException {
        Path p = Path.of(path).toAbsolutePath().normalize();
        if (root != null && !p.startsWith(root)) {
            throw new IOException("이 폴더는 볼 수 없어요: " + path);
        }
        return p;
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
