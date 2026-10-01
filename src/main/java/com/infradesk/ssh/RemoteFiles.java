package com.infradesk.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;

/** 서버의 파일 접근(SFTP). 블로킹이므로 EDT 밖에서 호출한다. */
public interface RemoteFiles extends AutoCloseable {

    /** 로그인 디렉터리. 예: "/home/ubuntu". */
    String home() throws IOException;

    /** 디렉터리 항목. 디렉터리가 먼저, 그다음 이름순이며 "."와 ".."는 제외한다. */
    List<RemoteFile> list(String directory) throws IOException;

    boolean exists(String path) throws IOException;

    /** @param progress 지금까지 복사한 바이트 수를 받는다 */
    void download(String remotePath, Path localFile, LongConsumer progress) throws IOException;

    void upload(Path localFile, String remotePath, LongConsumer progress) throws IOException;

    /** 파일 또는 빈 디렉터리를 삭제한다. */
    void delete(RemoteFile file) throws IOException;

    @Override
    void close();

    /** 디렉터리와 이름을 '/'로 잇는다. 슬래시가 겹치지 않게 한다. */
    static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    /** 상위 디렉터리. "/"의 상위는 "/"다. */
    static String parent(String path) {
        if (path.equals("/") || !path.contains("/")) {
            return "/";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = trimmed.lastIndexOf('/');
        return i <= 0 ? "/" : trimmed.substring(0, i);
    }

    /** 원격 파일 이름을 로컬 파일 이름으로 안전하게 쓸 수 있게 바꾼 것. */
    static String safeLocalName(String remoteName) {
        String cleaned = remoteName.replace('/', '_').replace('\\', '_').replace(':', '_').strip();
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? "download" : cleaned;
    }
}
