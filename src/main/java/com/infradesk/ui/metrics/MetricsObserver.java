package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;

/**
 * 모니터링 탭이 보여 주는 값을 다른 화면(개요 탭의 "지금 상태")이 같이 보여 주려고 받는 알림.
 *
 * <p>개요가 서버에 따로 묻지 않는다: 모니터링 탭이 읽은 기록이나 실시간 샘플이 그대로 넘어온다. 그래서 개요를 열어
 * 두어도 서버에 가는 조회는 늘지 않는다.
 */
public interface MetricsObserver {

    /** 차트에 새 점들이 들어왔다(기록을 불러왔거나 실시간 샘플이 하나 늘었다). */
    void onData(Metrics metrics);

    /** 보여 줄 데이터가 없다("불러오는 중…", "서버가 실행 중일 때 표시돼요" 등). */
    void onEmpty(String message);

    /** 상태 줄 문구가 바뀌었다(예: "OCI 모니터링 · 1분 간격 · 15:31 갱신"). */
    void onStatus(String text, boolean error);
}
