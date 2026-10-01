package com.infradesk.ui.metrics;

import java.util.Locale;

/** 메트릭 값의 숫자 형식. */
final class Formats {

    private Formats() {
    }

    static String percent(double v) {
        return Math.round(v) + "%";
    }

    /** 초당 바이트를 B/s, KB/s, MB/s, GB/s로(1000 단위. 네트워크 도구가 보여주는 방식이라 축 눈금이 딱 떨어진다). */
    static String rate(double bytesPerSec) {
        String[] units = {"B/s", "KB/s", "MB/s", "GB/s"};
        double v = bytesPerSec;
        int u = 0;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        return (v >= 100 || u == 0 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v))
                + " " + units[u];
    }
}
