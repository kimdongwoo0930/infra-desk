package com.infradesk.ui.metrics;

import java.util.Locale;

/** Number formatting for metric values. */
final class Formats {

    private Formats() {
    }

    static String percent(double v) {
        return Math.round(v) + "%";
    }

    /** Bytes per second in B/s, KB/s, MB/s, GB/s (1000 base, as network tools show it, so axis ticks land on round numbers). */
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
