package com.infradesk.service;

/** UI가 요청할 수 있는 전원 동작. */
public enum ServerAction {
    START("시작", "시작할까요?", false),
    STOP("정지", "정지할까요?", true),
    REBOOT("재부팅", "재부팅할까요?", true);

    private final String label;
    private final String question;
    private final boolean needsConfirmation;

    ServerAction(String label, String question, boolean needsConfirmation) {
        this.label = label;
        this.question = question;
        this.needsConfirmation = needsConfirmation;
    }

    public String label() {
        return label;
    }

    public String question() {
        return question;
    }

    /** 서비스를 중단시키는 동작은 실행 전에 확인을 받는다(CLAUDE.md 보안 규칙). */
    public boolean needsConfirmation() {
        return needsConfirmation;
    }
}
