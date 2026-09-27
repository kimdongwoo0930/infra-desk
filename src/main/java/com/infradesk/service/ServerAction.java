package com.infradesk.service;

/** Power actions the UI can request. */
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

    /** Disruptive actions ask before running (CLAUDE.md security rules). */
    public boolean needsConfirmation() {
        return needsConfirmation;
    }
}
