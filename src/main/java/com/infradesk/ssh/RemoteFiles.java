package com.infradesk.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;

/** 서버의 파일 접근(SFTP). 블로킹이므로 EDT 밖에서 호출한다. */
public interface RemoteFiles extends AutoCloseable {

    /** 로그인 디렉터리. 예: "/home/ubuntu". */
    String home() throws IOException;

    /** 디렉터리 항목. 폴더가 먼저, 그다음 이름순. "."와 ".."은 뺀다. */
    List<RemoteFile> list(String directory) throws IOException;

    boolean exists(String path) throws IOException;

    /** 항목 하나의 현재 상태(크기·수정 시각·권한). 없으면 {@link java.nio.file.NoSuchFileException} 같은 {@link IOException}. */
    RemoteFile stat(String path) throws IOException;

    /** @param progress 지금까지 복사한 바이트 수를 받는다. 예외를 던지면 전송이 중단된다 */
    default void download(String remotePath, Path localFile, LongConsumer progress) throws IOException {
        download(remotePath, localFile, 0, progress);
    }

    /**
     * 이어받기. {@code offset}바이트는 이미 {@code localFile}에 있다고 보고 그 뒤부터 받아 붙인다.
     * {@code progress}는 {@code offset}을 포함한 누적 바이트를 받는다.
     */
    void download(String remotePath, Path localFile, long offset, LongConsumer progress) throws IOException;

    default void upload(Path localFile, String remotePath, LongConsumer progress) throws IOException {
        upload(localFile, remotePath, 0, progress);
    }

    /** 이어올리기. 서버에 {@code offset}바이트가 이미 있다고 보고 그 뒤부터 올린다. */
    void upload(Path localFile, String remotePath, long offset, LongConsumer progress) throws IOException;

    /** 파일 하나나 빈 폴더, 또는 심볼릭 링크 자체를 지운다. */
    void delete(RemoteFile file) throws IOException;

    /**
     * 폴더와 그 안의 모든 것을 지운다. 심볼릭 링크는 따라 들어가지 않고 링크만 지운다(링크가
     * 가리키는 다른 폴더의 내용을 지우지 않도록).
     */
    default void deleteRecursively(RemoteFile file) throws IOException {
        if (file.directory() && !file.symlink()) {
            for (RemoteFile child : list(file.path())) {
                deleteRecursively(child);
            }
        }
        delete(file);
    }

    void mkdir(String path) throws IOException;

    /** 이름 바꾸기·이동. 대상이 이미 있으면 서버에 따라 실패한다. */
    void rename(String from, String to) throws IOException;

    /** @param permissions POSIX 권한 비트(예: 0644) */
    void chmod(String path, int permissions) throws IOException;

    @Override
    void close();

    /** 디렉터리와 이름을 '/'로 잇는다. 슬래시가 겹치지 않게 한다. */
    static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    /** 상위 디렉터리. "/"는 "/"로 남는다. */
    static String parent(String path) {
        if (path.equals("/") || !path.contains("/")) {
            return "/";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = trimmed.lastIndexOf('/');
        return i <= 0 ? "/" : trimmed.substring(0, i);
    }

    /** 원격 이름을 로컬 파일 이름으로 안전하게 바꾼다. */
    static String safeLocalName(String remoteName) {
        String cleaned = remoteName.replace('/', '_').replace('\\', '_').replace(':', '_').strip();
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? "download" : cleaned;
    }
}
