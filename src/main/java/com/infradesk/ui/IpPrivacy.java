package com.infradesk.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용자가 보이게 하기 전까지 화면의 공인 IPv4 주소를 숨긴다(스크린샷과 화면 공유용).
 * 앱 전체에 적용되며 실행할 때마다 숨김 상태로 시작한다. 사설 주소(10/8, 172.16/12,
 * 192.168/16, 127/8, 100.64/10)는 밖에서 접근할 수 없으므로 그대로 보여준다. 다만 직접 연결 서버의
 * 주소({@link #protect})는 예외다. 그것은 사용자 자신의 기기이므로 호스트 이름이든 IP든
 * 무엇이든 숨긴다.
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

    /** EDT에서 호출해야 한다. 모든 곳이 다시 그려지도록 리스너에 알린다. */
    public static void setRevealed(boolean value) {
        revealed = value;
        for (Runnable r : LISTENERS) {
            r.run();
        }
    }

    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** 표시용 주소 하나: 숨김 상태이면 "•••.•••.•••.104". */
    public static String display(String ip) {
        return ip == null ? null : mask(ip);
    }

    /** 보이게 하기 전까지 이 주소(직접 연결 서버의 호스트 이름이나 IP)를 항상 숨긴다. */
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

    /** 긴 텍스트 안의 모든 공인 IPv4와 보호 대상 주소를 가린다(예: "ubuntu@203.0.113.24:22"). */
    public static String mask(String text) {
        if (text == null || revealed) {
            return text;
        }
        // 긴 것부터 처리해서 "mac-mini.tail.ts.net"이 "mac-mini"보다 우선하게 한다.
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
