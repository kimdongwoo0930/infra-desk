package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemInfo;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.time.LocalTime;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Box;
import java.awt.Component;

/** Main window: title bar on top, sidebar on the left, content on the right. */
public class MainFrame extends JFrame {

    private final TitleBar titleBar = new TitleBar();
    private final Sidebar sidebar = new Sidebar();
    private final JPanel content = new JPanel(new BorderLayout());

    public MainFrame() {
        super("InfraDesk");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 600));
        setSize(1280, 800);
        setLocationRelativeTo(null);

        getRootPane().putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);
        if (SystemInfo.isMacFullWindowContentSupported) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
            getRootPane().putClientProperty(FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING,
                    FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING_MEDIUM);
        }

        content.setBackground(Theme.APP_BG);
        content.add(emptyState(), BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.APP_BG);
        root.add(titleBar, BorderLayout.NORTH);
        root.add(sidebar, BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);

        titleBar.refreshButton().addActionListener(e -> titleBar.markRefreshed(LocalTime.now()));
        sidebar.onAddAccount(() -> JOptionPane.showMessageDialog(this,
                "계정 추가 화면은 2단계에서 만들어요.", "준비 중", JOptionPane.INFORMATION_MESSAGE));
        titleBar.markRefreshed(LocalTime.now());
    }

    private static JPanel emptyState() {
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel icon = new JLabel(Icons.get("cloud", 48, Theme.TEXT_MUTED));
        JLabel title = new JLabel("서버를 선택하세요");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel hint = new JLabel("계정을 추가하면 서버 목록이 왼쪽에 표시돼요.");
        hint.setForeground(Theme.TEXT_MUTED);

        for (JLabel l : new JLabel[] {icon, title, hint}) {
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        box.add(icon);
        box.add(Box.createVerticalStrut(12));
        box.add(title);
        box.add(Box.createVerticalStrut(6));
        box.add(hint);
        wrap.add(box);
        return wrap;
    }
}
