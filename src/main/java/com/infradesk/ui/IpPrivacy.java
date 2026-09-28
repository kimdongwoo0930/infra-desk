package com.infradesk.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides public IPv4 addresses on screen (for screenshots and screen sharing) until the user
 * reveals them. App-wide, starts hidden on every launch. Private addresses (10/8, 172.16/12,
 * 192.168/16, 127/8, 100.64/10) are shown as-is since they're not reachable from outside, except
 * the addresses of directly connected servers ({@link #protect}): those are the user's own
 * machines, so their host name or IP is hidden whatever it is.
 */
public final class IpPrivacy {

    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean revealed;
    private static final java.util.Set<String> PROTECTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private IpPrivacy() {
    }

    public static boolean isRevealed() {
        return revealed;
    }

    /** Must be called on the EDT; notifies listeners so every place re-renders. */
    public static void setRevealed(boolean value) {
        revealed = value;
        for (Runnable r : LISTENERS) {
            r.run();
        }
    }

    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** One address for display: "•••.•••.•••.104" while hidden. */
    public static String display(String ip) {
        return ip == null ? null : mask(ip);
    }

    /** Always hide this address (a directly connected server's host name or IP) until revealed. */
    public static void protect(String host) {
        if (host != null && host.length() >= 2) {
            PROTECTED.add(host);
        }
    }

    /** "mac-mini" → "m•••••", "192.168.0.20" → "•••.•••.•••.20". */
    static String hide(String host) {
        Matcher ip = IPV4.matcher(host);
        if (ip.matches()) {
            return "•••.•••.•••." + ip.group(4);
        }
        return host.charAt(0) + "•••••";
    }

    /** Masks every public IPv4 and protected address inside a longer text (e.g. "ubuntu@203.0.113.24:22"). */
    public static String mask(String text) {
        if (text == null || revealed) {
            return text;
        }
        // Longest first, so "mac-mini.tail.ts.net" wins over "mac-mini".
        for (String host : PROTECTED.stream().sorted((a, b) -> b.length() - a.length()).toList()) {
            if (text.contains(host)) {
                text = text.replace(host, hide(host));
            }
        }
        Matcher m = IPV4.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, isPublic(m) ? "•••.•••.•••." + m.group(4) : m.group());
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isPublic(Matcher m) {
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        if (a > 255 || b > 255 || Integer.parseInt(m.group(3)) > 255 || Integer.parseInt(m.group(4)) > 255) {
            return false;
        }
        return !(a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127) || a == 0);
    }
}
