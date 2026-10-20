package com.infradesk.alert;

/**
 * 보낼 알림의 종류. 웹훅 URL은 비밀값이라 키체인에 따로 저장한다.
 *
 * @param enabled        전체 스위치
 * @param serverDown     실행 중이던 서버가 앱의 요청 없이 멈춘 경우(와 돌아온 경우)
 * @param accountError   계정에 연결할 수 없는 경우(와 복구된 경우)
 * @param cpuHigh        CPU가 {@code cpuThreshold}% 이상인 상태가 {@code cpuMinutes}번 연속 확인된 경우
 */
public record AlertSettings(boolean enabled, boolean serverDown, boolean accountError, boolean cpuHigh,
                            int cpuThreshold, int cpuMinutes) {

    public static final AlertSettings DEFAULT = new AlertSettings(false, true, true, true, 90, 5);

    public AlertSettings {
        cpuThreshold = Math.max(1, Math.min(100, cpuThreshold));
        cpuMinutes = Math.max(1, Math.min(60, cpuMinutes));
    }
}
