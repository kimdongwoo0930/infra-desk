package com.infradesk.ui.files;

/**
 * 고친 내용이 있는 편집 창을 닫을 때 "저장하시겠습니까?"에서 고른 것.
 * 버튼은 [저장] [저장 안 함] [취소] 순서이고, <b>창의 X로 닫으면 "저장 안 함"으로 닫는다</b>(사용자가 정했다).
 */
enum CloseChoice {
    SAVE, DISCARD, CANCEL;

    /** 이 순서의 버튼 목록. */
    static final String[] BUTTONS = {"저장", "저장 안 함", "취소"};

    /** {@code JOptionPane.showOptionDialog}가 돌려준 값을 고른 것으로 바꾼다. X로 닫으면 -1이 온다. */
    static CloseChoice fromOption(int option) {
        return switch (option) {
            case 0 -> SAVE;
            case 2 -> CANCEL;
            default -> DISCARD;   // 1(저장 안 함)과 X로 닫기(-1)
        };
    }
}
