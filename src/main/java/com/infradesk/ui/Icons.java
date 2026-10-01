package com.infradesk.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import java.awt.Color;

/** src/main/resources/com/infradesk/ui/icons의 윤곽선 SVG 아이콘. 사용할 때마다 색을 바꿀 수 있다. */
public final class Icons {

    private Icons() {
    }

    public static FlatSVGIcon get(String name, int size, Color color) {
        FlatSVGIcon icon = new FlatSVGIcon("com/infradesk/ui/icons/" + name + ".svg", size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color));
        return icon;
    }

    public static FlatSVGIcon get(String name, int size) {
        return get(name, size, Theme.TEXT_SECONDARY);
    }
}
