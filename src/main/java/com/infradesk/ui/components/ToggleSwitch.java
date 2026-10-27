package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JToggleButton;

/**
 * 켜고 끄는 스위치 (예: 읽기 전용). 시안: 34×20 알약 모양 트랙 안에 지름 14의 손잡이.
 *
 * <p>{@link JToggleButton}을 바탕으로 해서 클릭, 스페이스 키, {@code isSelected()}, 이벤트를 그대로
 * 쓰고, 모양만 직접 그린다. "켜짐"은 선택된 상태({@code isSelected()})다.
 */
public class ToggleSwitch extends JToggleButton {

    private static final int TRACK_W = 34;
    private static final int TRACK_H = 20;
    private static final int KNOB = 14;
    /** 손잡이와 트랙 가장자리 사이 간격. 끔이면 왼쪽(3), 켬이면 오른쪽(34 - 14 - 3 = 17)에 붙는다. */
    private static final int KNOB_INSET = 3;

    public ToggleSwitch() {
        this(false);
    }

    public ToggleSwitch(boolean on) {
        setSelected(on);
        // 버튼이 기본으로 그리는 배경, 테두리, 포커스 표시를 모두 끄고 paintComponent에서 직접 그린다.
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    // 크기는 시안대로 고정한다. 레이아웃이 늘려도 스위치 모양은 변하지 않는다.
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(TRACK_W, TRACK_H);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 비활성(준비 중)이면 반투명하게 그려서 눌 수 없음이 보이게 한다.
            if (!isEnabled()) {
                g2.setComposite(AlphaComposite.SrcOver.derive(0.45f));
            }
            boolean on = isSelected();
            int y = (getHeight() - TRACK_H) / 2;

            // 트랙: 호의 지름을 높이와 같게 주면 양 끝이 둥근 알약이 된다.
            g2.setColor(on ? Theme.ACCENT : Theme.SECONDARY_BUTTON_BORDER);
            g2.fillRoundRect(0, y, TRACK_W, TRACK_H, TRACK_H, TRACK_H);

            // 손잡이
            int knobX = on ? TRACK_W - KNOB - KNOB_INSET : KNOB_INSET;
            g2.setColor(on ? Color.WHITE : Theme.TEXT);
            g2.fillOval(knobX, y + KNOB_INSET, KNOB, KNOB);

            // 키보드로 포커스가 왔을 때만 트랙 둘레에 가는 선을 그린다.
            if (isFocusOwner() && isEnabled()) {
                g2.setColor(Theme.ACCENT_TEXT);
                g2.drawRoundRect(0, y, TRACK_W - 1, TRACK_H - 1, TRACK_H, TRACK_H);
            }
        } finally {
            g2.dispose();
        }
    }
}
