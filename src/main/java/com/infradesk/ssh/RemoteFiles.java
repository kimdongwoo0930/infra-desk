package com.infradesk.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;

/** File access on a server (SFTP). Blocking; call off the EDT. */
public interface RemoteFiles extends AutoCloseable {

    /** The login directory, e.g. "/home/ubuntu". */
    String home() throws IOException;

    /** Directory entries, directories first then by name; "." and ".." excluded. */
    List<RemoteFile> list(String directory) throws IOException;

    boolean exists(String path) throws IOException;

    /** @param progress receives the number of bytes copied so far */
    void download(String remotePath, Path localFile, LongConsumer progress) throws IOException;

    void upload(Path localFile, String remotePath, LongConsumer progress) throws IOException;

    /** Deletes a file or an empty directory. */
    void delete(RemoteFile file) throws IOException;

    @Override
    void close();

    /** Joins a directory and a name with '/', without doubling slashes. */
    static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    /** Parent directory; "/" stays "/". */
    static String parent(String path) {
        if (path.equals("/") || !path.contains("/")) {
            return "/";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = trimmed.lastIndexOf('/');
        return i <= 0 ? "/" : trimmed.substring(0, i);
    }

    /** A remote name made safe to use as a local file name. */
    static String safeLocalName(String remoteName) {
        String cleaned = remoteName.replace('/', '_').replace('\\', '_').replace(':', '_').strip();
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? "download" : cleaned;
    }
}
