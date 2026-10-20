package com.infradesk.ssh;

/**
 * 서버 하나에서 명령 하나를 실행한 결과.
 *
 * @param exitCode    종료 코드. 알 수 없으면 -1(시간 초과, 연결 실패)
 * @param output      stdout/stderr를 합친 출력. 잘렸을 수 있다
 * @param error       사용자에게 보여줄 실패 메시지. 명령이 실행됐다면 null
 * @param failureKind 연결하지 못했을 때 그 종류(인증 실패, 호스트 키 문제 등). 자동으로 되풀이하는
 *                    작업이 "다시 시도해도 소용없는 실패"인지 가르는 데 쓴다. 연결 단계 실패가 아니면 null
 */
public record ExecResult(int exitCode, String output, String error, boolean truncated,
                         SshException.Kind failureKind) {

    public ExecResult(int exitCode, String output, String error, boolean truncated) {
        this(exitCode, output, error, truncated, null);
    }

    public boolean succeeded() {
        return error == null && exitCode == 0;
    }

    public static ExecResult failure(String error) {
        return new ExecResult(-1, "", error, false);
    }

    public static ExecResult failure(String error, SshException.Kind kind) {
        return new ExecResult(-1, "", error, false, kind);
    }
}
