package com.infradesk.core;

/**
 * {@link Account}의 서버가 어디서 오는지. {@link CloudProvider} 구현을 고르는 데 쓴다.
 * {@link #SSH}는 클라우드가 아니다. 계정 하나가 SSH로 직접 닿는 기기 한 대(집 서버, Mac mini)이므로
 * 전원 제어와 클라우드 메트릭이 없다.
 */
public enum ProviderType {
    ORACLE("Oracle Cloud", true),
    AWS("AWS", false),
    GCP("GCP", false),
    SSH("직접 연결", true);

    private final String displayName;
    private final boolean supported;

    ProviderType(String displayName, boolean supported) {
        this.displayName = displayName;
        this.supported = supported;
    }

    public String displayName() {
        return displayName;
    }

    /** API가 있는 클라우드(직접 연결 기기가 아님). */
    public boolean isCloud() {
        return this != SSH;
    }

    /** provider를 통한 시작 / 정지 / 재부팅. */
    public boolean hasPowerControl() {
        return isCloud();
    }

    /** provider가 주는 CPU/메모리/네트워크 기록(그렇지 않으면 SSH 실시간 모드뿐). */
    public boolean hasCloudMetrics() {
        return isCloud();
    }

    /** provider 구현이 이미 있는지. */
    public boolean isSupported() {
        return supported;
    }
}
