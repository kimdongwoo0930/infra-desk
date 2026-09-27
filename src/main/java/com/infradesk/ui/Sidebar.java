package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/** 260px left column: server search, account groups, and the add-account / settings footer. */
public class Sidebar extends JPanel {

    private final JTextField search = new JTextField();
    private final JPanel listArea = new JPanel(new BorderLayout());
    private final RoundedPanel addAccount;
    private final JButton settingsButton = Buttons.icon("settings", "설정", 34);

    public Sidebar() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(260, 0));
        setBackground(Theme.PANEL_BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.DIVIDER));

        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "서버 검색");
        search.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22; margin: 0,4,0,4");
        search.getAccessibleContext().setAccessibleName("서버 검색");
        search.setPreferredSize(new Dimension(0, 32));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        top.add(search);

        listArea.setOpaque(false);
        listArea.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JLabel empty = new JLabel("<html><center>등록된 계정이 없어요.<br>아래에서 계정을 추가하세요.</center></html>",
                SwingConstants.CENTER);
        empty.setForeground(Theme.TEXT_MUTED);
        listArea.add(empty, BorderLayout.CENTER);

        addAccount = new RoundedPanel(new FlowLayout(FlowLayout.CENTER, 6, 8), null, Theme.DASHED_BORDER, Theme.ARC_CONTROL);
        addAccount.setDashed(true);
        addAccount.setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
        addAccount.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JLabel addLabel = new JLabel("계정 추가", Icons.get("plus", 14), SwingConstants.LEFT);
        addLabel.setForeground(Theme.TEXT_SECONDARY);
        addAccount.add(addLabel);
        addAccount.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                addAccount.setBorderColor(Theme.TEXT_MUTED);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                addAccount.setBorderColor(Theme.DASHED_BORDER);
            }
        });

        settingsButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, null);
        settingsButton.putClientProperty(FlatClientProperties.STYLE, "background: null; borderColor: #43454A");

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        footer.add(addAccount, BorderLayout.CENTER);
        footer.add(settingsButton, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(listArea, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    public void onAddAccount(Runnable action) {
        addAccount.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        });
    }

    public JButton settingsButton() {
        return settingsButton;
    }
}
