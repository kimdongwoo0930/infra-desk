package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 큰 차트의 덧그림: 알림 기준선과, 세 차트가 같은 시점을 함께 가리키는 선. */
class ChartOverlayTest {

    private static final Instant END = Instant.parse("2026-01-01T12:00:00Z");

    private static List<Metrics.Sample> samples(double base) {
        List<Metrics.Sample> list = new ArrayList<>();
        for (int i = 60; i >= 0; i--) {
            list.add(new Metrics.Sample(END.minusSeconds(60L * i), base + 10 * Math.sin(i / 5.0)));
        }
        return list;
    }

    private static BufferedImage paint(ZoomChartPanel panel) {
        BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        panel.paint(g);
        g.dispose();
        return img;
    }

    private static ZoomChartPanel chart(MetricKind kind, List<List<Metrics.Sample>> data) {
        ZoomChartPanel panel = new ZoomChartPanel(kind);
        panel.setSize(800, 300);
        panel.setData(data);
        paint(panel);   // 처음 그려야 축 좌표가 정해진다(painted)
        return panel;
    }

    private static void move(ZoomChartPanel panel, int x) {
        MouseEvent e = new MouseEvent(panel, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, 100, 0, false);
        for (var l : panel.getMouseMotionListeners()) {
            l.mouseMoved(e);
        }
    }

    private static void exit(ZoomChartPanel panel) {
        MouseEvent e = new MouseEvent(panel, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false);
        for (var l : panel.getMouseListeners()) {
            l.mouseExited(e);
        }
    }

    /** 두 그림에서 다른 픽셀이 하나라도 있는지. */
    private static boolean differs(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 한 가로줄에서 다른 픽셀의 수. */
    private static int differingInRow(BufferedImage a, BufferedImage b, int y) {
        int n = 0;
        for (int x = 0; x < a.getWidth(); x++) {
            if (a.getRGB(x, y) != b.getRGB(x, y)) {
                n++;
            }
        }
        return n;
    }

    // ── 알림 기준선 ─────────────────────────────────────────────

    @Test
    void 알림_기준이_있으면_그_높이에_점선이_그려진다() {
        ZoomChartPanel cpu = chart(MetricKind.CPU, List.of(samples(30)));
        BufferedImage without = paint(cpu);
        int y = (int) Math.round(cpu.getChart().getScreenYFromChart(90));

        cpu.setAlertThreshold(90.0);
        BufferedImage with = paint(cpu);

        assertTrue(differingInRow(without, with, y) > 20, "90% 높이의 가로줄이 점선으로 바뀌어야 한다");
    }

    @Test
    void 기준을_없애면_점선이_사라진다() {
        ZoomChartPanel cpu = chart(MetricKind.CPU, List.of(samples(30)));
        BufferedImage plain = paint(cpu);
        cpu.setAlertThreshold(90.0);

        cpu.setAlertThreshold(null);

        assertEquals(false, differs(plain, paint(cpu)), "알림이 꺼져 있으면 기준선도 없다");
    }

    @Test
    void 메모리와_네트워크처럼_y_범위가_고정되지_않은_차트에는_기준선을_그리지_않는다() {
        ZoomChartPanel network = chart(MetricKind.NETWORK, List.of(samples(50), samples(20)));
        BufferedImage plain = paint(network);

        network.setAlertThreshold(90.0);

        assertEquals(false, differs(plain, paint(network)));
    }

    // ── 같은 시점 ───────────────────────────────────────────────

    @Test
    void 한_차트에_마우스를_올리면_묶인_다른_차트도_같은_시각을_가리킨다() {
        HoverSync sync = new HoverSync();
        ZoomChartPanel cpu = chart(MetricKind.CPU, List.of(samples(30)));
        ZoomChartPanel memory = chart(MetricKind.MEMORY, List.of(samples(40)));
        cpu.setSync(sync);
        memory.setSync(sync);
        assertNull(memory.hoverTime());

        move(cpu, 400);

        assertTrue(cpu.hoverTime() != null);
        assertEquals(cpu.hoverTime(), memory.hoverTime(), "메모리 차트는 마우스가 없는데도 같은 시각을 가리킨다");
    }

    @Test
    void 가리키는_다른_차트에도_세로선이_그려진다() {
        HoverSync sync = new HoverSync();
        ZoomChartPanel cpu = chart(MetricKind.CPU, List.of(samples(30)));
        ZoomChartPanel memory = chart(MetricKind.MEMORY, List.of(samples(40)));
        cpu.setSync(sync);
        memory.setSync(sync);
        BufferedImage before = paint(memory);

        move(cpu, 400);
        BufferedImage after = paint(memory);

        assertTrue(differs(before, after), "마우스가 없는 메모리 차트에도 선과 값 상자가 나와야 한다");
    }

    @Test
    void 마우스가_차트를_벗어나면_모든_차트에서_선이_사라진다() {
        HoverSync sync = new HoverSync();
        ZoomChartPanel cpu = chart(MetricKind.CPU, List.of(samples(30)));
        ZoomChartPanel memory = chart(MetricKind.MEMORY, List.of(samples(40)));
        cpu.setSync(sync);
        memory.setSync(sync);
        BufferedImage plain = paint(memory);
        move(cpu, 400);

        exit(cpu);

        assertNull(memory.hoverTime());
        assertEquals(false, differs(plain, paint(memory)));
    }

    @Test
    void 확대해서_다른_구간을_보는_차트는_범위_밖의_시각을_그리지_않는다() {
        HoverSync sync = new HoverSync();
        ZoomChartPanel cpu = chart(MetricKind.CPU, List.of(samples(30)));
        ZoomChartPanel memory = chart(MetricKind.MEMORY, List.of(samples(40)));
        cpu.setSync(sync);
        memory.setSync(sync);
        BufferedImage plain = paint(memory);

        // 메모리 차트가 보는 범위보다 한참 전의 시각을 가리킨다
        sync.hover(END.minusSeconds(60L * 600).toEpochMilli());

        assertEquals(false, differs(plain, paint(memory)), "자기 범위 밖이면 그리지 않는다");
    }

    @Test
    void 묶지_않은_차트는_자기_마우스_위치만_따른다() {
        // 예전 방식(확대 창 등)과 같은 동작: 묶음이 없어도 자기 위에서는 선이 나온다.
        ZoomChartPanel solo = chart(MetricKind.CPU, List.of(samples(30)));
        BufferedImage before = paint(solo);

        move(solo, 400);

        assertNotEquals(null, solo.hoverTime());
        assertTrue(differs(before, paint(solo)));
    }

    @Test
    void 같은_시각을_다시_알려도_다시_그리지_않는다() {
        HoverSync sync = new HoverSync();
        int[] repaints = {0};
        sync.register(new java.awt.Canvas() {
            @Override
            public void repaint() {
                repaints[0]++;
            }
        });

        sync.hover(1000);
        sync.hover(1000);
        sync.hover(2000);

        assertEquals(2, repaints[0], "시각이 바뀔 때만 다시 그린다(마우스가 움직일 때마다 세 차트를 그리면 느려진다)");
    }
}
