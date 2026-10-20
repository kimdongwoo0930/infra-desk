package com.infradesk.ssh;

import java.time.Instant;

/**
 * 원격 디렉터리의 항목 하나.
 *
 * @param path     원격 절대 경로
 * @param modified 마지막 수정 시각. 없으면 null
 */
public record RemoteFile(String name, String path, boolean directory, long size, Instant modified) {
}
