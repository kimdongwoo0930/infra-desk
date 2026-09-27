package com.infradesk.ssh;

import java.time.Instant;

/**
 * An entry in a remote directory.
 *
 * @param path     absolute remote path
 * @param modified last modification time, or null
 */
public record RemoteFile(String name, String path, boolean directory, long size, Instant modified) {
}
