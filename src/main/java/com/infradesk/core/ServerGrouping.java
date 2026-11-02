package com.infradesk.core;

import java.util.Arrays;

/** 사이드바와 개요에서 서버를 묶는 방법. 설정 "일반"에서 고른다. */
public enum ServerGrouping {
    /** 계정마다 한 묶음. 처음 값이다. */
    ACCOUNT("계정별"),
    /** 실행 중 / 정지 / 그 밖의 상태로 묶는다. */
    STATUS("상태별"),
    /** 묶지 않고 한 줄로 늘어놓는다. */
    NONE("묶지 않음");

    private final String label;

    ServerGrouping(String label) {
        this.label = label;
    }

    /** 화면에 보이는 이름. 선택 목록의 글자와 같다. */
    public String label() {
        return label;
    }

    /** 저장된 이름({@link #name()})을 되살린다. 모르는 값이면 {@code fallback}. */
    public static ServerGrouping fromName(String name, ServerGrouping fallback) {
        return Arrays.stream(values()).filter(g -> g.name().equals(name)).findFirst().orElse(fallback);
    }

    /** 선택 목록의 글자({@link #label()})로 찾는다. 없으면 {@code fallback}. */
    public static ServerGrouping fromLabel(String label, ServerGrouping fallback) {
        return Arrays.stream(values()).filter(g -> g.label.equals(label)).findFirst().orElse(fallback);
    }
}
