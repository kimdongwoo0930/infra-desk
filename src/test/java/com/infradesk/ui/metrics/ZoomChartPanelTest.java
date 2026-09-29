package com.infradesk.ui.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infradesk.core.Metrics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ZoomChartPanelTest {

    private static final Instant END = Instant.parse("2026-01-01T12:00:00Z");

    private static List<Metrics.Sample> samples(int minutes, double base) {
        List<Metrics.Sample> list = new ArrayList<>();
        for (int i = minutes; i >= 0; i--) {
            list.add(new Metrics.Sample(END.minusSeconds(60L * i), base + 10 * Math.sin(i / 5.0)));
        }
        return list;
    }

    private static void paint(ZoomChartPanel panel) {
        BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        panel.paint(g);
        g.dispose();
    }

    private static void wheel(ZoomChartPanel panel, int x, double rotation) {
        MouseWheelEvent e = new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, x, 100, x, 100, 0,
                false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (int) rotation, rotation);
        for (var l : panel.getMouseWheelListeners()) {
            l.mouseWheelMoved(e);
        }
    }

    private static ZoomChartPanel panel() {
        ZoomChartPanel panel = new ZoomChartPanel(MetricKind.NETWORK);
        panel.setSize(800, 400);
        panel.setData(List.of(samples(60, 50), samples(60, 20)));
        paint(panel);
        return panel;
    }

    @Test
    void axisMappingRoundTrips() {
        ZoomChartPanel panel = panel();
        double t0 = END.minusSeconds(3600).toEpochMilli();
        double t1 = END.toEpochMilli();
        double left = panel.getChart().getScreenXFromChart(t0);
        double right = panel.getChart().getScreenXFromChart(t1);
        assertTrue(left >= 0 && left < right && right <= 800, left + " .. " + right);
        int mid = (int) ((left + right) / 2);
        assertEquals((t0 + t1) / 2, panel.getChart().getChartXFromCoordinate(mid), (t1 - t0) / (right - left) * 2);
    }

    @Test
    void wheelZoomsInAndDoubleClickResets() {
        ZoomChartPanel panel = panel();
        AtomicInteger changes = new AtomicInteger();
        panel.onZoomChanged(z -> changes.incrementAndGet());
        assertFalse(panel.isZoomed());
        wheel(panel, 400, -3);
        assertTrue(panel.isZoomed());
        assertEquals(1, changes.get());
        panel.resetZoom();
        assertFalse(panel.isZoomed());
        assertEquals(2, changes.get());
    }

    @Test
    void zoomingOutWhenFullAsksToWidenTheRange() {
        ZoomChartPanel panel = panel();
        AtomicInteger widen = new AtomicInteger();
        panel.onZoomOutFull(widen::incrementAndGet);
        wheel(panel, 400, 3);
        wheel(panel, 400, 3);
        assertEquals(1, widen.get(), "a burst of wheel events widens only once");
        assertFalse(panel.isZoomed());
    }

    @Test
    void newDataKeepsTheZoomedView() {
        ZoomChartPanel panel = panel();
        wheel(panel, 400, -3);
        assertTrue(panel.isZoomed());
        panel.setData(List.of(samples(61, 50), samples(61, 20)));
        assertTrue(panel.isZoomed());
    }
}
