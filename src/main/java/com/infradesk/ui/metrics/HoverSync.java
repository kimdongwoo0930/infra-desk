package com.infradesk.ui.metrics;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * 세 차트가 "같은 시점"을 함께 가리키게 하는 공유 상태 (디자인 v2). 마우스가 올라간 차트가 가리키는 시각을
 * 여기에 알리면, 등록된 모든 차트가 그 시각에 세로선과 값 상자를 그린다.
 *
 * <p>차트는 확대 범위가 서로 다를 수 있어서, 화면 좌표가 아니라 <b>시각(epoch 밀리초)</b>을 공유한다. 받은 차트는
 * 그 시각을 자기 좌표로 바꿔 그리고, 자기가 보이는 범위 밖이면 그리지 않는다.
 */
final class HoverSync {

    private final List<Component> charts = new ArrayList<>();
    private Long time;

    /** 이 그룹에 차트를 넣는다. 가리키는 시각이 바뀔 때마다 다시 그려진다. */
    void register(Component chart) {
        charts.add(chart);
    }

    /** 가리키는 시각을 정한다(epoch 밀리초). 같은 값이면 다시 그리지 않는다. */
    void hover(long epochMillis) {
        if (time != null && time == epochMillis) {
            return;
        }
        time = epochMillis;
        charts.forEach(Component::repaint);
    }

    /** 마우스가 차트를 벗어났다: 모든 차트에서 선과 상자를 지운다. */
    void clear() {
        if (time == null) {
            return;
        }
        time = null;
        charts.forEach(Component::repaint);
    }

    /** 지금 가리키는 시각. 없으면 null. */
    Long time() {
        return time;
    }
}
