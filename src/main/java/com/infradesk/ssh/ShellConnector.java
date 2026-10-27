package com.infradesk.ssh;

/** 대화형 셸을 연다. 블로킹이므로 EDT에서 호출하지 않는다. */
public interface ShellConnector {

    ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows);

    /** {@code command}를 PTY와 함께 실행한다. 그 명령으로 시작하는 셸처럼 동작한다(예: {@code docker exec -it}). */
    default ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows, String command) {
        throw new UnsupportedOperationException("PTY commands are not supported by " + getClass().getSimpleName());
    }

    /** PTY 없이 명령을 실행한다. 세션의 출력이 명령의 stdout과 stderr다. */
    ShellSession exec(SshTarget target, HostKeyPrompt prompt, String command);

    /**
     * 목적을 밝히고 명령을 실행한다. 풀을 쓰는 구현은 같은 서버·목적의 연결을 재사용한다. 풀이 없는 구현은
     * 목적을 무시한다.
     */
    default ShellSession exec(Purpose purpose, SshTarget target, HostKeyPrompt prompt, String command) {
        return exec(target, prompt, command);
    }

    /** SFTP 세션을 연다. */
    RemoteFiles sftp(SshTarget target, HostKeyPrompt prompt);

    /** 목적을 밝히고 SFTP 세션을 연다. */
    default RemoteFiles sftp(Purpose purpose, SshTarget target, HostKeyPrompt prompt) {
        return sftp(target, prompt);
    }

    /**
     * 앱 창이 메뉴 막대로만 숨었는지 알린다. 풀을 쓰는 구현은 숨은 동안 조회(QUERY) 연결만 남기고, 쓰고 있지
     * 않은 다른 연결은 닫는다(쓰는 중인 연결은 끝난 뒤에 닫는다).
     */
    default void setBackground(boolean background) {
    }

    /** 풀이 지금 열어 두고 있는 연결들(읽기 전용). 풀을 쓰지 않는 구현은 빈 목록이다. */
    default java.util.List<OpenConnection> openConnections() {
        return java.util.List.of();
    }

    /**
     * 사용자가 풀의 연결 하나를 끊는다. 놀고 있는 연결은 바로 끊고, 쓰는 중인 연결은 {@code force}일 때만 끊는다
     * (아니면 그대로 두고 false). 풀을 쓰지 않는 구현은 끊을 것이 없다.
     */
    default boolean disconnect(String serverId, Purpose purpose, boolean force) {
        return false;
    }

    /** 풀 연결을 모두 끊는다. {@code force}가 아니면 쓰는 중인 연결은 둔다. @return 끊은 연결 수 */
    default int disconnectAll(boolean force) {
        return 0;
    }

    /** 이 서버의 설정이 바뀌었거나 서버가 지워졌다. 이 서버의 풀 연결을 모두 끊는다. */
    default void invalidate(String serverId) {
    }
}
