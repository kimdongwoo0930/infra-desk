package com.infradesk.core;

/**
 * 앱이 서버를 바꿀 수 있는지. 앱을 켤 때는 항상 {@link #READ_ONLY}이고, {@link #MANAGE}로 바꾸려면 관리 비밀번호가 필요하다.
 */
public enum AccessMode {
    /** 보기만 한다. 서버를 바꾸거나 접속하는 기능(시작·정지, 터미널, 파일, 컨테이너 조작 등)은 화면에서 숨는다. */
    READ_ONLY("읽기"),
    /** 모든 기능을 쓴다. */
    MANAGE("관리");

    private final String label;

    AccessMode(String label) {
        this.label = label;
    }

    /** 화면에 보이는 이름(타이틀바 토글의 글자). */
    public String label() {
        return label;
    }
}
