package com.infradesk.ui.settings;

import com.infradesk.ui.Theme;
import com.infradesk.ui.components.ToggleSwitch;

import java.awt.Dimension;
import java.time.Duration;
import javax.swing.JComboBox;
import javax.swing.JLabel;

/** 설정 행의 오른쪽에 놓는 컨트롤을 만드는 도우미들. */
final class Controls {

    private Controls() {
    }

    static ToggleSwitch toggle(boolean on) {
        return new ToggleSwitch(on);
    }

    /** 선택 목록. 목록이 하나뿐이면(아직 고를 수 없는 설정의 현재 값) 그 값이 보인다. */
    static JComboBox<String> choice(int width, String... items) {
        JComboBox<String> box = new JComboBox<>(items);
        box.setPreferredSize(new Dimension(width, Theme.BUTTON_HEIGHT));
        // macOS 기본 모양(위아래 화살표) 대신 시안처럼 아래 화살표 하나로.
        box.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "buttonStyle: button");
        return box;
    }

    /** 바꿀 수 없는 값을 오른쪽에 글자로 보여 준다. */
    static JLabel value(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT);
        return l;
    }

    /** "30초", "1분", "10분", "12시간": 초·분·시간 중 딱 떨어지는 가장 큰 단위로. */
    static String duration(Duration d) {
        long seconds = d.toSeconds();
        if (seconds >= 3600 && seconds % 3600 == 0) {
            return seconds / 3600 + "시간";
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return seconds / 60 + "분";
        }
        return seconds + "초";
    }
}
