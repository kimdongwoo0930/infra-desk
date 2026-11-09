package com.infradesk.ui.files;

/**
 * 파일 표에서 "이미 하나만 선택된 행을 한 번 더 누르면 선택이 풀린다"를 판단한다.
 *
 * <p>JTable은 마우스를 누르는 순간 선택을 바꾸므로, 누르기 <b>직전</b>의 선택 상태를 {@link #pressed}로 기억해 둔다
 * (표의 마우스 처리보다 먼저 불러야 한다). 선택을 풀지는 {@link #shouldClear}로 묻는다.
 *
 * <p>풀지 않는 경우: Shift·Ctrl/Cmd를 누른 클릭(여러 개 고르기), 오른쪽 클릭, 여러 개가 선택돼 있던 경우,
 * 선택돼 있지 않던 행, 더블클릭(열기). 화면 없이 시험할 수 있게 Swing에 기대지 않는다.
 */
final class ClickToDeselect {

    private int armedRow = -1;

    /**
     * 마우스를 누르기 직전의 상태를 기록한다.
     *
     * @param row           누른 행(빈 곳이면 -1)
     * @param rowSelected   그 행이 이미 선택돼 있었나
     * @param selectedCount 선택돼 있던 행의 수
     * @param primaryButton 왼쪽 버튼인가
     * @param modifier      Shift/Ctrl/Cmd를 같이 눌렀나
     * @param popupTrigger  우클릭 메뉴를 여는 누르기인가(macOS의 Ctrl+클릭 포함)
     */
    void pressed(int row, boolean rowSelected, int selectedCount, boolean primaryButton, boolean modifier, boolean popupTrigger) {
        armedRow = row >= 0 && rowSelected && selectedCount == 1 && primaryButton && !modifier && !popupTrigger ? row : -1;
    }

    /**
     * 클릭이 끝났을 때 선택을 풀어야 하나.
     *
     * @param row        클릭한 행
     * @param clickCount 연속 클릭 횟수(2 이상은 더블클릭이라 풀지 않는다)
     */
    boolean shouldClear(int row, int clickCount) {
        boolean clear = clickCount == 1 && row >= 0 && row == armedRow;
        armedRow = -1;
        return clear;
    }
}
