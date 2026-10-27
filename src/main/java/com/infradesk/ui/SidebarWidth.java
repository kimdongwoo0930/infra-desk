package com.infradesk.ui;

/**
 * 사이드바 폭의 규칙. 마우스로 폭을 바꿀 때 "언제 이름만 보이고, 언제 닫히는지"를 값으로만 정해 둔다
 * (화면 코드와 떼어 두면 테스트할 수 있다).
 *
 * <pre>
 *   폭 ≥ 200            전체: 점 · 이름 · CPU%, 계정 헤더의 리전, 하단 "계정·서버 추가" 글자 버튼
 *   90 ≤ 폭 < 200        이름만: CPU%·상태 글자·리전을 숨기고, 하단 버튼은 아이콘만. 이름이 길면 "이름…"
 *   폭 < 90 (끌어서)    자동으로 닫힌다
 *   최대 420
 * </pre>
 */
final class SidebarWidth {

    private SidebarWidth() {
    }

    /** 처음 폭, 그리고 닫기 전의 폭을 모를 때 펼치는 폭. */
    static final int DEFAULT = 260;
    /** 끌어서 늘릴 수 있는 최대 폭. */
    static final int MAX = 420;
    /** 이보다 좁게 끌면 닫는다. */
    static final int COLLAPSE_BELOW = 90;
    /** 이보다 좁으면 이름만 보인다. */
    static final int COMPACT_BELOW = 200;
    /** 닫았다 다시 펼 때 돌아갈 폭으로 기억해 둘 수 있는 최소 폭(너무 좁은 폭으로 되돌아가면 어색하다). */
    static final int REMEMBER_MIN = 140;

    /** 끌던 폭이 닫을 만큼 좁은지. 0 이하는 이미 닫힌 상태이거나 아직 배치 전이라 세지 않는다. */
    static boolean shouldCollapse(int width) {
        return width > 0 && width < COLLAPSE_BELOW;
    }

    /** 이름만 보여 줄 만큼 좁은지. */
    static boolean isCompact(int width) {
        return width < COMPACT_BELOW;
    }

    /** 최대 폭을 넘지 않게 맞춘다. */
    static int clamp(int width) {
        return Math.min(MAX, width);
    }

    /** 다시 펼 때의 폭: 기억해 둔 폭이 쓸 만하면 그것, 아니면 기본 폭. */
    static int restoreWidth(int remembered) {
        return remembered >= REMEMBER_MIN ? clamp(remembered) : DEFAULT;
    }
}
