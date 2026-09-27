package com.infradesk.alert;

/**
 * Which alerts to send. The webhook URL is secret and stored separately in the keychain.
 *
 * @param enabled        master switch
 * @param serverDown     a running server stopped without the app asking it to (and when it's back)
 * @param accountError   an account can't be reached (and when it recovers)
 * @param cpuHigh        CPU stayed at or above {@code cpuThreshold}% for {@code cpuMinutes} checks
 */
public record AlertSettings(boolean enabled, boolean serverDown, boolean accountError, boolean cpuHigh,
                            int cpuThreshold, int cpuMinutes) {

    public static final AlertSettings DEFAULT = new AlertSettings(false, true, true, true, 90, 5);

    public AlertSettings {
        cpuThreshold = Math.max(1, Math.min(100, cpuThreshold));
        cpuMinutes = Math.max(1, Math.min(60, cpuMinutes));
    }
}
