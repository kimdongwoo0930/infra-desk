package com.infradesk.core;

import java.util.Arrays;

/** 개요에서 서버를 보여 주는 방식. 개요 위쪽의 "카드 | 목록" 전환에서 고른다. */
public enum OverviewView {
    /** 서버마다 카드 한 장. 처음 값이다. */
    CARDS("카드"),
    /** 서버마다 표의 한 줄. 서버가 많을 때 한 화면에 더 많이 보인다. */
    LIST("목록");

    private final String label;

    OverviewView(String label) {
        this.label = label;
    }

    /** 화면에 보이는 이름. */
    public String label() {
        return label;
    }

    /** 저장된 이름({@link #name()})을 되살린다. 모르는 값이면 {@code fallback}. */
    public static OverviewView fromName(String name, OverviewView fallback) {
        return Arrays.stream(values()).filter(v -> v.name().equals(name)).findFirst().orElse(fallback);
    }
}
