package com.infradesk.ui.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZoomWindowTest {

    private static ZoomWindow window() {
        return new ZoomWindow(0, 1000, 100);
    }

    @Test
    void startsFull() {
        ZoomWindow w = window();
        assertTrue(w.isFull());
        assertEquals(0, w.min());
        assertEquals(1000, w.max());
    }

    @Test
    void zoomInKeepsTheTimeUnderTheCursor() {
        ZoomWindow w = window();
        assertTrue(w.zoom(0.5, 250));
        assertEquals(500, w.span(), 1e-9);
        // 250 was 25% into the view, so it stays 25% into the new view
        assertEquals(125, w.min(), 1e-9);
        assertEquals(625, w.max(), 1e-9);
        assertFalse(w.isFull());
    }

    @Test
    void zoomInStopsAtTheMinimumSpan() {
        ZoomWindow w = window();
        for (int i = 0; i < 20; i++) {
            w.zoom(0.5, 500);
        }
        assertEquals(100, w.span(), 1e-9);
    }

    @Test
    void zoomOutReturnsToFullAndThenReportsIt() {
        ZoomWindow w = window();
        w.zoom(0.5, 500);
        assertTrue(w.zoom(3, 500));
        assertTrue(w.isFull());
        assertFalse(w.zoom(1.5, 500), "already full: the caller may widen the range");
    }

    @Test
    void viewStaysInsideTheData() {
        ZoomWindow w = window();
        w.zoom(0.5, 0);
        assertEquals(0, w.min(), 1e-9);
        w.pan(-10_000);
        assertEquals(0, w.min(), 1e-9);
        w.pan(10_000);
        assertEquals(1000, w.max(), 1e-9);
        assertEquals(500, w.span(), 1e-9);
    }

    @Test
    void fullViewFollowsNewDataButZoomedViewStays() {
        ZoomWindow full = window();
        full.setFull(60, 1060, 100);
        assertEquals(60, full.min());
        assertEquals(1060, full.max());

        ZoomWindow zoomed = window();
        zoomed.zoom(0.5, 500);
        double min = zoomed.min();
        zoomed.setFull(60, 1060, 100);
        assertEquals(Math.max(60, min), zoomed.min(), 1e-9);
        assertEquals(500, zoomed.span(), 1e-9);
    }
}
