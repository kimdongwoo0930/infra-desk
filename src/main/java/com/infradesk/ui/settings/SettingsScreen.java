package com.infradesk.ui.settings;

import com.infradesk.alert.AlertService;
import com.infradesk.app.LaunchAtLogin;
import com.infradesk.service.UpdateService;
import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;

/**
 * 설정 화면 (디자인 v2): 왼쪽에 "← 개요로 돌아가기"와 항목 메뉴(일반 / 알림 / 보안·안전 / 연결·조회 / 업데이트·정보),
 * 오른쪽에 고른 항목 하나. 예전의 모달 창({@code SettingsDialog})을 메인 창 안의 화면으로 바꾼 것이다.
 *
 * <p>바꾼 설정은 바로 저장된다(저장 버튼이 없다). 에이전트 항목은 에이전트를 만들 때까지 메뉴에 넣지 않는다.
 */
public final class SettingsScreen extends JPanel {

    /** 왼쪽 메뉴의 폭. */
    static final int MENU_WIDTH = 260;

    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final List<SettingsPage> pages = new ArrayList<>();
    private final List<MenuButton> menu = new ArrayList<>();
    private final AboutPage about;
    private final ConnectionPage connectionPage;
    private String shownId;
    private Runnable onBack = () -> { };

    public SettingsScreen(AlertService alerts, boolean demoMode, UpdateService updates, LaunchAtLogin launchAtLogin,
                          Runnable checkNow, Runnable onAlertsChanged, ConnectionPage.Source connections) {
        super(new BorderLayout());
        setBackground(Theme.APP_BG);
        about = new AboutPage(updates, demoMode, checkNow);
        pages.add(new GeneralPage(launchAtLogin, demoMode));
        pages.add(new AlertsPage(alerts, demoMode, onAlertsChanged));
        pages.add(new SecurityPage());
        connectionPage = new ConnectionPage(connections);
        pages.add(connectionPage);
        pages.add(about);

        add(menuPanel(), BorderLayout.WEST);
        body.setOpaque(false);
        for (SettingsPage page : pages) {
            JScrollPane scroll = new JScrollPane(page);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(Theme.APP_BG);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            body.add(scroll, page.id());
        }
        add(body, BorderLayout.CENTER);
        show(GeneralPage.ID);
        // ESC로 설정에서 나간다(입력칸에서 눌러도 동작한다).
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "leave-settings");
        getActionMap().put("leave-settings", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                onBack.run();
            }
        });
    }

    private JPanel menuPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.PANEL_BG);
        panel.setPreferredSize(new Dimension(MENU_WIDTH, 0));
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.DIVIDER));

        JButton back = Buttons.ghost("개요로 돌아가기");
        back.setIcon(Icons.get("arrow-left", 15, Theme.TEXT_MUTED));
        back.setIconTextGap(8);
        back.setForeground(Theme.TEXT_MUTED);
        back.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        back.addActionListener(e -> onBack.run());

        JLabel title = new JLabel("설정");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 22f));
        title.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 0));

        JPanel items = new JPanel();
        items.setOpaque(false);
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        ButtonGroup group = new ButtonGroup();
        for (SettingsPage page : pages) {
            MenuButton button = new MenuButton(page.menuLabel());
            button.addActionListener(e -> show(page.id()));
            group.add(button);
            menu.add(button);
            items.add(button);
            items.add(Box.createVerticalStrut(6));
        }

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        back.setAlignmentX(LEFT_ALIGNMENT);
        title.setAlignmentX(LEFT_ALIGNMENT);
        items.setAlignmentX(LEFT_ALIGNMENT);
        top.setBorder(BorderFactory.createEmptyBorder(18, 12, 0, 12));
        top.add(back);
        top.add(title);
        top.add(items);

        JLabel note = new JLabel("<html>바꾼 설정은 바로 저장돼요. 비밀값은 금고(secrets.vault)에 암호화해 뒀어요.</html>");
        note.setForeground(Theme.TEXT_MUTED);
        note.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: -1");
        note.setBorder(BorderFactory.createEmptyBorder(0, 26, 24, 20));

        panel.add(top, BorderLayout.NORTH);
        panel.add(note, BorderLayout.SOUTH);
        return panel;
    }

    /** "← 개요로 돌아가기"를 눌렀을 때. */
    public void onBack(Runnable listener) {
        this.onBack = listener;
    }

    /** 항목 하나를 보여 준다({@link GeneralPage#ID} 등). 모르는 이름이면 아무것도 바꾸지 않는다. */
    public void show(String id) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id().equals(id)) {
                shownId = id;
                cards.show(body, id);
                if (ConnectionPage.ID.equals(id)) {
                    connectionPage.refresh();
                }
                menu.get(i).setSelected(true);
                return;
            }
        }
    }

    /** "연결·조회" 항목이 보이는 동안 열린 연결 표를 다시 그린다(주기적으로 불린다). 다른 항목이 보이면 아무것도 안 한다. */
    public void refreshConnections() {
        if (ConnectionPage.ID.equals(shownId)) {
            connectionPage.refresh();
        }
    }

    /** 지금 보이는 항목의 이름표. */
    public String shownId() {
        return shownId;
    }

    /** 업데이트 확인의 결과를 "업데이트·정보" 항목에 보여 준다(EDT에서 호출). */
    public void showUpdateResult(String message, boolean newer) {
        about.showUpdateResult(message, newer);
    }

    /** 메뉴에 보이는 항목 이름들(위에서부터). */
    public List<String> menuLabels() {
        return pages.stream().map(SettingsPage::menuLabel).toList();
    }

    /** 왼쪽 메뉴의 항목 하나: 고르면 파랑 바탕에 굵은 글씨. */
    private static final class MenuButton extends JToggleButton {
        MenuButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setHorizontalAlignment(LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 12));
            setAlignmentX(LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(MENU_WIDTH - 24, 40));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            // 글꼴과 글자색은 선택이 바뀔 때만 바꾼다(그리는 도중에 바꾸면 이전 모양이 남는다).
            addItemListener(e -> updateLook());
            updateLook();
        }

        private void updateLook() {
            setFont(getFont().deriveFont(isSelected() ? java.awt.Font.BOLD : java.awt.Font.PLAIN));
            setForeground(isSelected() ? Theme.TEXT : Theme.TEXT_MUTED);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected()) {
                g2.setColor(Theme.SELECTION_BG);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
            } else if (getModel().isRollover()) {
                g2.setColor(Theme.HOVER_BG);   // 사이드바 행과 같은 옅은 회색(ARGB 순서에 주의: 0xFFFFFF10은 불투명한 노란색이다)
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
