package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 40px top bar. The window uses full-window-content mode, so this bar doubles as the native title
 * bar and reserves space for the macOS traffic lights / Windows caption buttons.
 */
public class TitleBar extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final JLabel summary = new JLabel();
    private final JLabel lastRefresh = new JLabel();
    private final JButton refreshButton = Buttons.icon("refresh", "새로고침", 28);
    private final JButton backButton = new JButton("대시보드", Icons.get("arrow-left", 15));
    private final JLabel sessions = new JLabel();
    private final JPanel dashboardLeft;
    private final JPanel terminalLeft;

    public TitleBar(boolean demoMode) {
        super(new BorderLayout());
        setBackground(Theme.PANEL_BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));

        JPanel left = row(10);
        dashboardLeft = left;
        left.add(new JLabel(Icons.get("app", 18, Theme.ACCENT)));
        JLabel name = new JLabel("InfraDesk");
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        left.add(name);
        summary.setForeground(Theme.TEXT_MUTED);
        left.add(summary);
        if (demoMode) {
            JLabel demo = new JLabel("데모 모드");
            demo.setForeground(Theme.WARNING);
            demo.setToolTipText("가짜 데이터로 실행 중이에요. 실제 클라우드에 연결하지 않아요.");
            demo.putClientProperty(FlatClientProperties.STYLE,
                    "font: -2; border: 1,6,1,6,#5C4E2E,1,10");
            left.add(demo);
        }

        terminalLeft = row(10);
        backButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        backButton.setForeground(Theme.TEXT_SECONDARY);
        backButton.setIconTextGap(6);
        backButton.setFocusable(false);
        terminalLeft.add(backButton);
        JLabel slash = new JLabel("/");
        slash.setForeground(Theme.DASHED_BORDER);
        terminalLeft.add(slash);
        JLabel terminalTitle = new JLabel("SSH 터미널");
        terminalTitle.setFont(terminalTitle.getFont().deriveFont(Font.BOLD));
        terminalLeft.add(terminalTitle);
        terminalLeft.setVisible(false);

        JPanel leftWrap = row(0);
        leftWrap.add(placeholder("mac zeroInFullScreen"));
        leftWrap.add(Box.createHorizontalStrut(6));
        leftWrap.add(dashboardLeft);
        leftWrap.add(terminalLeft);

        JPanel right = row(12);
        lastRefresh.setForeground(Theme.TEXT_MUTED);
        lastRefresh.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        sessions.setForeground(Theme.TEXT_MUTED);
        sessions.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        sessions.setVisible(false);
        sessions.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        right.add(sessions);
        right.add(lastRefresh);
        right.add(refreshButton);
        right.add(placeholder("win"));

        add(leftWrap, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        setCounts(0, 0);
    }

    public JButton backButton() {
        return backButton;
    }

    /** Switches between the dashboard header and the "← 대시보드 / SSH 터미널" breadcrumb. */
    public void setTerminalMode(boolean terminal) {
        dashboardLeft.setVisible(!terminal);
        terminalLeft.setVisible(terminal);
        sessions.setVisible(terminal);
        lastRefresh.setVisible(!terminal);
        refreshButton.setVisible(!terminal);
        revalidate();
        repaint();
    }

    public void setSessionCount(int count) {
        sessions.setText("열린 세션 " + count + "개");
    }

    public JButton refreshButton() {
        return refreshButton;
    }

    public void setCounts(int servers, int accounts) {
        summary.setText("서버 " + servers + "대 · 계정 " + accounts + "개");
    }

    public void setRefreshing(boolean refreshing) {
        refreshButton.setEnabled(!refreshing);
        if (refreshing) {
            lastRefresh.setText("새로고침 중…");
        }
    }

    public void markRefreshed(LocalTime time) {
        lastRefresh.setText("마지막 새로고침 " + TIME.format(time));
    }

    private static JPanel row(int gap) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 6));
        p.setOpaque(false);
        return p;
    }

    /** Empty panel FlatLaf sizes to the window buttons on the given platform. */
    private static JPanel placeholder(String options) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, options);
        return p;
    }
}
