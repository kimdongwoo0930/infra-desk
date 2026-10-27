package com.infradesk.ui.containers;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 컨테이너 표에 보일 글자를 만든다. Docker가 주는 영어 문구를 시안(디자인 v2)의 한국어 표기로 바꾼다.
 * 해석하지 못하는 문구는 Docker가 준 그대로 돌려준다(틀리게 바꾸는 것보다 영어 그대로가 낫다).
 */
public final class ContainerFormat {

    private ContainerFormat() {
    }

    /** {@code Exited (0) 3 days ago}: 종료 코드와 지난 시간. */
    private static final Pattern EXITED = Pattern.compile("^Exited \\((-?\\d+)\\) (.+) ago$");
    /** {@code 3 days}, {@code 1 hour}, {@code 20 seconds}. */
    private static final Pattern COUNT_UNIT = Pattern.compile("^(\\d+) (second|minute|hour|day|week|month|year)s?$");
    /** {@code About an hour}, {@code About a minute}. 약 1이다. */
    private static final Pattern ABOUT = Pattern.compile("^About an? (second|minute|hour|day|week|month|year)$");

    /**
     * 컨테이너의 상태 글자. 멈춘 컨테이너만 바꾼다: {@code Exited (0) 3 days ago} → {@code 멈춤 (0) · 3일 전}.
     * 실행 중({@code Up 14 days})과 그 밖의 문구는 그대로 둔다(시안도 그대로다).
     */
    public static String status(String dockerStatus) {
        Matcher m = EXITED.matcher(dockerStatus.strip());
        if (!m.matches()) {
            return dockerStatus;
        }
        String ago = duration(m.group(2));
        return ago == null ? dockerStatus : "멈춤 (" + m.group(1) + ") · " + ago + " 전";
    }

    /** 지난 시간 문구를 한국어로: {@code 3 days}→{@code 3일}, {@code About an hour}→{@code 약 1시간}. 못 읽으면 null. */
    private static String duration(String text) {
        if (text.equals("Less than a second")) {
            return "방금";
        }
        Matcher count = COUNT_UNIT.matcher(text);
        if (count.matches()) {
            return count.group(1) + unit(count.group(2));
        }
        Matcher about = ABOUT.matcher(text);
        if (about.matches()) {
            return "약 1" + unit(about.group(1));
        }
        return null;
    }

    private static String unit(String english) {
        return switch (english) {
            case "second" -> "초";
            case "minute" -> "분";
            case "hour" -> "시간";
            case "day" -> "일";
            case "week" -> "주";
            case "month" -> "개월";
            default -> "년";
        };
    }

    /** 메모리: {@code 120MiB / 23.4GiB}에서 쓰는 양만, 숫자와 단위 사이를 띄워서. 값이 없으면 {@code —}. */
    static String memory(String memUsage) {
        if (memUsage == null || memUsage.isBlank()) {
            return "—";
        }
        return spaced(memUsage.split(" / ")[0]);
    }

    /** 네트워크: {@code 1.2MB / 3.4MB} → {@code 1.2 MB / 3.4 MB}. 값이 없으면 {@code —}. */
    static String network(String netIo) {
        if (netIo == null || netIo.isBlank()) {
            return "—";
        }
        return spaced(netIo);
    }

    /** 숫자 바로 뒤에 붙은 단위 앞에 공백을 넣는다: {@code 46MiB} → {@code 46 MiB}. */
    private static String spaced(String text) {
        return text.replaceAll("(\\d)([A-Za-z])", "$1 $2");
    }

    /** CPU 사용률 글자: {@code 12.8%}. 실행 중이 아니거나 알 수 없으면({@code -1}) {@code —}. */
    static String cpu(double percent) {
        return percent < 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", percent);
    }
}
