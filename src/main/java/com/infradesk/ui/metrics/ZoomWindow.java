package com.infradesk.ui.metrics;

/**
 * 차트의 보이는 시간 창: 전체 데이터 범위 안의 한 구간. 확대는 커서 아래의 시각을 고정하고,
 * 이동은 구간을 밀며, 구간은 데이터 밖으로 나가지 않는다. 시각은 epoch 밀리초다.
 */
final class ZoomWindow {

    private static final double EPSILON = 1e-6;

    private double fullMin;
    private double fullMax;
    private double minSpan;
    private double viewMin;
    private double viewMax;

    ZoomWindow(double fullMin, double fullMax, double minSpan) {
        this.fullMin = fullMin;
        this.fullMax = fullMax;
        this.minSpan = minSpan;
        reset();
    }

    double min() {
        return viewMin;
    }

    double max() {
        return viewMax;
    }

    double span() {
        return viewMax - viewMin;
    }

    boolean isFull() {
        return viewMin <= fullMin + EPSILON && viewMax >= fullMax - EPSILON;
    }

    void reset() {
        viewMin = fullMin;
        viewMax = fullMax;
    }

    /** 새 데이터가 도착했다: 전체 보기는 따라가고, 확대한 보기는 그대로 둔다(데이터 범위로 제한). */
    void setFull(double newMin, double newMax, double newMinSpan) {
        boolean wasFull = isFull();
        fullMin = newMin;
        fullMax = newMax;
        minSpan = newMinSpan;
        if (wasFull) {
            reset();
            return;
        }
        double span = Math.min(span(), fullMax - fullMin);
        viewMin = Math.max(fullMin, Math.min(viewMin, fullMax - span));
        viewMax = viewMin + span;
    }

    /**
     * @param factor  1보다 작으면 확대, 크면 축소
     * @param anchor  커서 아래에 고정되는 시각
     * @return 이미 전체를 보여주는 상태에서 축소를 요청하면 false(호출한 쪽이 기간을 넓힐 수 있다)
     */
    boolean zoom(double factor, double anchor) {
        if (factor > 1 && isFull()) {
            return false;
        }
        double fullSpan = fullMax - fullMin;
        double newSpan = Math.max(Math.min(minSpan, fullSpan), span() * factor);
        if (newSpan >= fullSpan) {
            reset();
            return true;
        }
        double fraction = span() <= 0 ? 0.5 : (anchor - viewMin) / span();
        fraction = Math.max(0, Math.min(1, fraction));
        moveTo(anchor - fraction * newSpan, newSpan);
        return true;
    }

    /** 구간을 {@code delta} 밀리초만큼 민다(양수 = 이후). 데이터의 끝에서 멈춘다. */
    void pan(double delta) {
        moveTo(viewMin + delta, span());
    }

    private void moveTo(double newMin, double newSpan) {
        double min = Math.max(fullMin, Math.min(newMin, fullMax - newSpan));
        viewMin = min;
        viewMax = min + newSpan;
    }
}
