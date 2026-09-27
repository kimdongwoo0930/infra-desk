package com.infradesk.core;

/** Provider-neutral server lifecycle state. Each provider maps its own state strings onto this enum. */
public enum ServerStatus {
    PROVISIONING("생성 중"),
    STARTING("시작 중"),
    RUNNING("실행 중"),
    STOPPING("정지 중"),
    STOPPED("정지됨"),
    REBOOTING("재부팅 중"),
    TERMINATING("삭제 중"),
    TERMINATED("삭제됨"),
    UNKNOWN("알 수 없음");

    private final String label;

    ServerStatus(String label) {
        this.label = label;
    }

    /** Korean label shown in the UI. */
    public String label() {
        return label;
    }

    /** True while the server is moving between stable states; callers poll faster in this case. */
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
