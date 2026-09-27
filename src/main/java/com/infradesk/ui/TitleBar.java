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

    public TitleBar() {
        super(new BorderLayout());
        setBackground(Theme.PANEL_BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));

        JPanel left = row(10);
        left.add(placeholder("mac zeroInFullScreen"));
        left.add(Box.createHorizontalStrut(6));
        left.add(new JLabel(Icons.get("app", 18, Theme.ACCENT)));
        JLabel name = new JLabel("InfraDesk");
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        left.add(name);
        summary.setForeground(Theme.TEXT_MUTED);
        left.add(summary);

        JPanel right = row(12);
        lastRefresh.setForeground(Theme.TEXT_MUTED);
        lastRefresh.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        right.add(lastRefresh);
        right.add(refreshButton);
        right.add(placeholder("win"));

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        setCounts(0, 0);
    }

    public JButton refreshButton() {
        return refreshButton;
    }

    public void setCounts(int servers, int accounts) {
        summary.setText("서버 " + servers + "대 · 계정 " + accounts + "개");
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
