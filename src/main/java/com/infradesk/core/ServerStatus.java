package com.infradesk.core;

/** provider와 무관한 서버 생명주기 상태. 각 provider는 자기 상태 문자열을 이 enum으로 대응시킨다. */
public enum ServerStatus {
    PROVISIONING("생성 중"),
    STARTING("시작 중"),
    RUNNING("실행 중"),
    STOPPING("정지 중"),
    STOPPED("정지됨"),
    REBOOTING("재부팅 중"),
    TERMINATING("삭제 중"),
    TERMINATED("삭제됨"),
    /** 응답하지 않은 직접 연결 기기(꺼짐, 잠자기, 또는 네트워크 끊김). */
    UNREACHABLE("응답 없음"),
    UNKNOWN("알 수 없음");

    private final String label;

    ServerStatus(String label) {
        this.label = label;
    }

    /** UI에 표시하는 한국어 라벨. */
    public String label() {
        return label;
    }

    /** 서버가 안정된 상태 사이를 이동하는 중이면 true. 이때 호출하는 쪽은 더 빠르게 폴링한다. */
    public boolean isTransitional() {
        return switch (this) {
            case PROVISIONING, STARTING, STOPPING, REBOOTING, TERMINATING -> true;
            default -> false;
        };
    }

    public boolean canStart() {
        return this == STOPPED;
    }

    public boolean canStop() {
        return this == RUNNING;
    }

    public boolean canReboot() {
        return this == RUNNING;
    }
}
