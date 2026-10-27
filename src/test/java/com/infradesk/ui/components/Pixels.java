package com.infradesk.ui.components;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/** 테스트 도구: 컴포넌트를 이미지에 그려서 픽셀 색을 읽는다. 창은 띄우지 않는다. */
final class Pixels {

    private Pixels() {
    }

    /** 컴포넌트를 권장 크기로 맞추고, 자식을 배치한 뒤, 투명한 배경 이미지에 그린다. */
    static BufferedImage paint(JComponent component) {
        Dimension size = component.getPreferredSize();
        component.setSize(size);
        component.doLayout();
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            component.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 픽셀의 알파(0이면 완전 투명, 255면 불투명). */
    static int alpha(int argb) {
        return argb >>> 24;
    }
}
