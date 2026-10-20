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

    /** SFTP 세션을 연다. */
    RemoteFiles sftp(SshTarget target, HostKeyPrompt prompt);
}
