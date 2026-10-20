package com.infradesk.ssh;

/**
 * 서버 하나에서 명령 하나를 실행한 결과.
 *
 * @param exitCode 종료 코드. 알 수 없으면 -1(시간 초과, 연결 실패)
 * @param output   stdout/stderr를 합친 출력. 잘렸을 수 있다
 * @param error    사용자에게 보여줄 실패 메시지. 명령이 실행됐다면 null
 */
public record ExecResult(int exitCode, String output, String error, boolean truncated) {

    public boolean succeeded() {
        return error == null && exitCode == 0;
    }

    public static ExecResult failure(String error) {
        return new ExecResult(-1, "", error, false);
    }
}
