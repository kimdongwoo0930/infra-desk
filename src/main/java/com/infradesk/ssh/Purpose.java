package com.infradesk.ssh;

import java.time.Duration;

/**
 * 서버에 접속하는 목적. 목적마다 SSH 연결을 따로 두고 그 위에서 채널을 여러 개 연다. 접속(TCP+인증)은 서버 로그에
 * 남으므로 같은 목적의 일은 한 연결을 재사용하고, 서로 영향을 주지 않도록 목적끼리는 연결을 나눈다(예: 큰 파일을
 * 전송하는 동안 조회가 느려지지 않게).
 *
 * <p>터미널 탭은 여기에 없다. 사용자가 열고 닫는 연결이라 풀 밖에서 따로 맺는다.
 */
public enum Purpose {
    /** 조회: 측정, 서버 정보, 컨테이너 목록·로그, 실시간 모드. 읽기만 하므로 연결이 죽었을 때 한 번 다시 시도한다. */
    QUERY(Duration.ofMinutes(15), true),
    /** 컨테이너 시작·정지처럼 서버를 바꾸는 명령. 응답만 못 받았을 뿐 이미 실행됐을 수 있어서 **자동으로 다시 시도하지 않는다.** */
    COMMAND(Duration.ofMinutes(5), false),
    /** SFTP 폴더 탐색과 파일 작업. */
    FILES(Duration.ofMinutes(5), false),
    /** SFTP 전송 대기열. 큰 전송이 다른 일을 막지 않게 따로 둔다. */
    TRANSFER(Duration.ofMinutes(5), false);

    private final Duration idleTimeout;
    private final boolean retriesOnce;

    Purpose(Duration idleTimeout, boolean retriesOnce) {
        this.idleTimeout = idleTimeout;
        this.retriesOnce = retriesOnce;
    }

    /** 쓰는 곳이 없어진 채로 이만큼 지나면 연결을 닫는다. */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    /** 재사용하던 연결이 죽어 있었을 때 새로 맺어서 한 번만 다시 시도해도 되는지. */
    public boolean retriesOnce() {
        return retriesOnce;
    }
}
