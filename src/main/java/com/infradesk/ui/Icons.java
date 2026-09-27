package com.infradesk.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import java.awt.Color;

/** Outline SVG icons from src/main/resources/com/infradesk/ui/icons, recolorable per use. */
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
