package com.infradesk.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.util.SystemInfo;

import java.awt.Color;
import java.awt.Font;
import java.util.Map;

/** docs/design/DESIGN.md의 색상표와 FlatLaf 설정. */
public final class Theme {

    public static final Color APP_BG = new Color(0x1E1F22);
    public static final Color PANEL_BG = new Color(0x2B2D30);
    public static final Color TERMINAL_BG = new Color(0x191A1C);
    public static final Color TERMINAL_BAR_BG = new Color(0x232427);
    public static final Color DIVIDER = new Color(0x393B40);
    public static final Color INPUT_BORDER = new Color(0x43454A);
    public static final Color SECONDARY_BUTTON_BORDER = new Color(0x4E5157);
    public static final Color DASHED_BORDER = new Color(0x55585E);
    public static final Color TEXT = new Color(0xDFE1E5);
    public static final Color TEXT_SECONDARY = new Color(0xB4B8BF);
    public static final Color TEXT_MUTED = new Color(0x8C8F94);
    public static final Color ACCENT = new Color(0x3B73E0);
    public static final Color SELECTION_BG = new Color(0x2E436E);
    public static final Color RUNNING_DOT = new Color(0x5FB865);
    public static final Color RUNNING_BADGE_BG = new Color(0x1F3A24);
    public static final Color RUNNING_BADGE_TEXT = new Color(0x7FD184);
    public static final Color DANGER_BORDER = new Color(0x6B3A3A);
    public static final Color DANGER_TEXT = new Color(0xF08A8A);
    public static final Color WARNING = new Color(0xE5C07B);

    public static final int ARC_CONTROL = 6;
    public static final int ARC_CARD = 8;
    public static final int ARC_DIALOG = 12;
    public static final int BUTTON_HEIGHT = 34;

    private Theme() {
    }

    /** 룩앤필을 설치한다. Swing 컴포넌트를 만들기 전에 실행해야 한다. */
    public static void install() {
        FlatLaf.setGlobalExtraDefaults(Map.ofEntries(
                Map.entry("@background", hex(APP_BG)),
                Map.entry("@foreground", hex(TEXT)),
                Map.entry("@accentColor", hex(ACCENT)),
                Map.entry("@selectionBackground", hex(SELECTION_BG)),
                Map.entry("Component.borderColor", hex(INPUT_BORDER)),
                Map.entry("Component.disabledBorderColor", hex(DIVIDER)),
                Map.entry("Component.focusWidth", "0"),
                Map.entry("Component.innerFocusWidth", "1"),
                Map.entry("Component.arc", String.valueOf(ARC_CONTROL)),
                Map.entry("Button.arc", String.valueOf(ARC_CONTROL)),
                Map.entry("TextComponent.arc", String.valueOf(ARC_CONTROL)),
                Map.entry("Button.borderColor", hex(SECONDARY_BUTTON_BORDER)),
                Map.entry("Button.background", "@background"),
                Map.entry("Separator.foreground", hex(DIVIDER)),
                Map.entry("Label.disabledForeground", hex(TEXT_MUTED)),
                Map.entry("TitlePane.background", hex(PANEL_BG)),
                Map.entry("TitlePane.inactiveBackground", hex(PANEL_BG)),
                // macOS의 FlatLaf는 기본적으로 모든 팝업을 별도의 네이티브 창으로 표시한다(그림자와
                // 둥근 테두리를 위해). 모달 다이얼로그 안에서는 그 창 때문에 다이얼로그가 키보드/마우스
                // 입력을 받지 못했다. 일반 Swing 팝업은 창 안에 그려진다.
                Map.entry("Popup.dropShadowPainted", "false"),
                Map.entry("ScrollBar.width", "10"),
                Map.entry("ScrollBar.thumbArc", "999"),
                Map.entry("ScrollBar.thumbInsets", "2,2,2,2")));

        if (SystemInfo.isMacOS) {
            FlatMacDarkLaf.setup();
        } else {
            FlatDarculaLaf.setup();
        }
        warmUpGlyphCache();
        com.infradesk.ui.components.TextUndo.install();
        com.infradesk.ui.components.TextContextMenu.install();
    }

    /**
     * macOS JDK의 글리프 캐시 버그를 우회한다: 어떤 글꼴 크기에서 처음 그린 문자열에
     * 한글 바로 뒤의 '.'나 따옴표가 있으면 그 문장부호가 빈 글리프로 캐시되어, 이후 그 크기의
     * 모든 '.'가 공백으로 그려진다("README.md"가 "README md"로 보인다). UI가 쓰는 크기, 스타일, 배율,
     * 안티앨리어싱 모드마다 ASCII 문장부호를 먼저 그려 두면 캐시가 올바른 글리프로 채워진다.
     */
    private static void warmUpGlyphCache() {
        if (!SystemInfo.isMacOS) {
            return;
        }
        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) {
            return;
        }
        String sample = "README.md v1.2 'a' \"b\" : ; , ! ? ( ) [ ] - _ / @ # % & * + = ~ ...";
        Object[] aaModes = {
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_GASP,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT};
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            for (double scale : new double[] {1, 2}) {
                for (Object aa : aaModes) {
                    for (int size = 9; size <= 24; size++) {
                        for (int style : new int[] {java.awt.Font.PLAIN, java.awt.Font.BOLD}) {
                            java.awt.Graphics2D gg = (java.awt.Graphics2D) g.create();
                            gg.scale(scale, scale);
                            gg.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, aa);
                            gg.setFont(base.deriveFont(style, (float) size));
                            gg.drawString(sample, 0, 0);
                            gg.dispose();
                        }
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** IP, 명령어, 터미널 텍스트용 고정폭 글꼴. */
    public static Font monoFont(float size) {
        String family = SystemInfo.isMacOS ? "Menlo" : "Consolas";
        return new Font(family, Font.PLAIN, 1).deriveFont(size);
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }
}
