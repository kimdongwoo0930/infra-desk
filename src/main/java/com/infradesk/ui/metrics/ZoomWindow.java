package com.infradesk.ui.metrics;

/**
 * The visible time window of a chart: a view inside the full data range. Zoom keeps the time under
 * the cursor fixed, pan slides the view, and the view never leaves the data. Times are epoch millis.
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

    /** New data arrived: a full view follows it, a zoomed view stays where it is (clamped to the data). */
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
     * @param factor  below 1 zooms in, above 1 zooms out
     * @param anchor  time that stays under the cursor
     * @return false when asked to zoom out while already showing everything (the caller may widen the range)
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

    /** Slides the view by {@code delta} millis (positive = later), stopping at the data's edges. */
    void pan(double delta) {
        moveTo(viewMin + delta, span());
    }

    private void moveTo(double newMin, double newSpan) {
        double min = Math.max(fullMin, Math.min(newMin, fullMax - newSpan));
        viewMin = min;
        viewMax = min + newSpan;
    }
}
