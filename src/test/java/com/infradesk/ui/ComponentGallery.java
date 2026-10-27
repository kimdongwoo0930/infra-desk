package com.infradesk.ui;

import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.FilterChip;
import com.infradesk.ui.components.Meter;
import com.infradesk.ui.components.SegmentedControl;
import com.infradesk.ui.components.StatusBadge;
import com.infradesk.ui.components.StatusBadge.Kind;
import com.infradesk.ui.components.StatusBar;
import com.infradesk.ui.components.TabBar;
import com.infradesk.ui.components.ToggleSwitch;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 개발 도구: 디자인 v2의 공통 부품을 상태별로 한 장에 모아 그린다 → build/snapshots/v2-components.png.
 * 시안(docs/design/v2/images)과 나란히 놓고 모양을 비교하는 용도다. {@code UiSnapshot}이 부른다.
 * 새 부품을 만들면 여기에 한 줄씩 추가한다.
 */
final class ComponentGallery {

    private static final int WIDTH = 720;

    private ComponentGallery() {
    }

    /** Swing 화면 스레드(EDT)에서 불러야 한다. Theme.install() 뒤여야 한다. */
    static void render(File dir) throws IOException {
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBackground(Theme.APP_BG);
        page.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        // 배지: 5가지 종류
        page.add(row("배지 (StatusBadge)",
                badge(Kind.SUCCESS, "실행 중"), badge(Kind.WARNING, "확인 필요 1"), badge(Kind.DANGER, "연결 실패"),
                badge(Kind.INFO, "새 버전"), badge(Kind.NEUTRAL, "정지됨"), badge(Kind.NEUTRAL, "4")));

        // 스위치: 꺼짐, 켜짐, 준비 중(꺼짐), 준비 중(켜짐 모양)
        ToggleSwitch comingOn = new ToggleSwitch(true);
        page.add(row("스위치 (ToggleSwitch)\n꺼짐 · 켜짐 · 준비 중 둘",
                new ToggleSwitch(false), new ToggleSwitch(true),
                ComingSoon.apply(new ToggleSwitch(false)), ComingSoon.apply(comingOn)));

        // 칩: "전체"가 선택된 상태
        ButtonGroup group = new ButtonGroup();
        FilterChip[] chips = {chip("전체", 6, group), chip("실행 중", 4, group), chip("정지", 2, group),
                chip("확인 필요", 2, group), chip("즐겨찾기", null, group)};
        chips[0].setSelected(true);
        page.add(row("거르기 칩 (FilterChip)", chips));

        // 구간 선택: "1시간"이 선택된 상태
        SegmentedControl range = new SegmentedControl(List.of("실시간", "1시간", "24시간", "7일"));
        range.setSelectedIndex(1);
        page.add(row("구간 선택 (SegmentedControl)", range));

        // 막대: 낮음, 중간, 경고 직전, 경고
        page.add(row("막대 (Meter)\n30 · 60 · 84 · 90%",
                meter(30), meter(60), meter(84), meter(90)));

        // 탭 줄: 개요가 선택, 컨테이너·터미널에 개수, 로그는 준비 중
        TabBar tabs = new TabBar();
        tabs.addTab("overview", "개요");
        tabs.addTab("monitoring", "모니터링");
        tabs.addTab("containers", "컨테이너");
        tabs.addTab("files", "파일");
        tabs.addTab("terminal", "터미널");
        tabs.addTab("logs", "로그");
        tabs.setCount("containers", 4);
        tabs.setCount("terminal", 2);
        tabs.setComingSoon("logs");
        page.add(row("탭 줄 (TabBar)", tabs));

        // 상태 막대: 왼쪽 현재 상태, 오른쪽 설정·버전 (SSH 연결 수는 읽을 방법이 없어 준비 중)
        StatusBar status = new StatusBar();
        ComingSoon.apply(status.addLeft("SSH 연결 —"));
        status.addLeft("실시간 꺼짐");
        status.addRight("상태 30초 · 메트릭 1분마다");
        status.addRight("v1.1.9");
        status.setPreferredSize(new Dimension(440, 26));
        page.add(row("상태 막대 (StatusBar)", status));

        // 준비 중: 버튼, 배지
        // 화면이 실제로 쓰는 버튼 도우미(Buttons)로 만들어야 비활성 모양이 앱과 같다.
        JButton logs = Buttons.secondary("로그", null);
        page.add(row("준비 중 (ComingSoon)\n비활성 버튼 · 배지", ComingSoon.apply(logs), ComingSoon.badge()));

        JFrame frame = new JFrame();
        frame.setContentPane(page);
        int height = page.getPreferredSize().height;
        frame.setSize(WIDTH, height);
        frame.addNotify();
        page.setSize(WIDTH, height);
        layoutAll(page);

        BufferedImage img = new BufferedImage(WIDTH * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);   // 화면 배율 2배(레티나)로 그려서 시안 이미지와 같은 선명도로 본다
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        page.paint(g);
        g.dispose();
        dir.mkdirs();
        ImageIO.write(img, "png", new File(dir, "v2-components.png"));
        frame.dispose();
    }

    /** 왼쪽에 이름표, 오른쪽에 부품들을 가로로 놓은 한 줄. */
    private static JPanel row(String title, JComponent... parts) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        JLabel label = new JLabel("<html>" + title.replace("\n", "<br>") + "</html>");
        label.setForeground(Theme.TEXT_MUTED);
        label.setPreferredSize(new Dimension(200, 36));
        row.add(label, BorderLayout.WEST);
        JPanel items = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        items.setOpaque(false);
        for (JComponent p : parts) {
            items.add(p);
        }
        row.add(items, BorderLayout.CENTER);
        return row;
    }

    private static StatusBadge badge(Kind kind, String text) {
        StatusBadge b = new StatusBadge();
        b.setKind(kind, text);
        return b;
    }

    private static FilterChip chip(String label, Integer count, ButtonGroup group) {
        FilterChip c = new FilterChip(label);
        c.setCount(count);
        group.add(c);
        return c;
    }

    private static Meter meter(double percent) {
        Meter m = new Meter();
        m.setPercent(percent);
        m.setPreferredSize(new Dimension(80, 6));
        return m;
    }

    /** 창을 띄우지 않고 그리므로, 자식까지 직접 배치를 돌려 준다. */
    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container cc) {
                layoutAll(cc);
            }
        }
    }
}
