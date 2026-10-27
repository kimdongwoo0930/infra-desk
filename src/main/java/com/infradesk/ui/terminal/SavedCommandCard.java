package com.infradesk.ui.terminal;

import com.infradesk.ssh.SavedCommand;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * 저장된 명령어 카드 (디자인 v2): 이름, 그 아래 고정폭 명령어, 오른쪽에 [▶] 실행 버튼. 카드 어디를 눌러도 실행하고,
 * 우클릭하면 편집·삭제 메뉴가 나온다.
 *
 * <p>준비 중(v2): 이름 옆의 "이 서버 / 모든 서버" 배지. 저장된 명령어에 범위 정보가 없어서(id, name, command뿐) 아직
 * 그리지 않는다. 범위를 저장하게 되면 이 자리에 배지를 놓는다.
 */
final class SavedCommandCard extends RoundedPanel {

    private static final Color NORMAL = new Color(0x1E1F22);
    private static final Color HOVER = new Color(0x26282B);

    SavedCommandCard(SavedCommand command, Runnable run, JPopupMenu menu) {
        super(new BorderLayout(10, 0), NORMAL, Theme.DIVIDER, Theme.ARC_CARD);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("클릭하면 열린 터미널에서 실행해요: " + command.command());
        getAccessibleContext().setAccessibleName(command.name() + ": " + command.command());

        // 긴 명령어가 카드를 넓히지 않고 "…"로 끝나도록 라벨 두 개를 BorderLayout 가운데에 둔다.
        JLabel name = new JLabel(command.name());
        name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        JLabel text = new JLabel(command.command());
        text.setFont(Theme.monoFont(11.5f));
        text.setForeground(Theme.TEXT_MUTED);
        JPanel lines = new JPanel();
        lines.setOpaque(false);
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        lines.add(name);
        lines.add(javax.swing.Box.createVerticalStrut(3));
        lines.add(text);
        name.setAlignmentX(LEFT_ALIGNMENT);
        text.setAlignmentX(LEFT_ALIGNMENT);
        // BoxLayout은 자식의 선호 폭을 따라 넓어지므로, 폭은 부모(BorderLayout)가 주는 만큼만 쓰게 한다.
        name.setMinimumSize(new Dimension(0, name.getPreferredSize().height));
        text.setMinimumSize(new Dimension(0, text.getPreferredSize().height));
        add(lines, BorderLayout.CENTER);

        JButton play = new JButton(com.infradesk.ui.Icons.get("play", 14, Theme.TEXT));
        play.setToolTipText("실행");
        play.getAccessibleContext().setAccessibleName(command.name() + " 실행");
        play.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,
                "background: #00000000; borderWidth: 1; borderColor: #4E5157; hoverBackground: #FFFFFF10; arc: 8; margin: 0,0,0,0");
        play.setFocusable(false);
        play.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        play.setPreferredSize(new Dimension(30, 30));
        play.addActionListener(e -> run.run());
        JPanel east = new JPanel(new java.awt.GridBagLayout());
        east.setOpaque(false);
        east.add(play);
        add(east, BorderLayout.EAST);

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!e.isPopupTrigger() && e.getButton() == MouseEvent.BUTTON1) {
                    run.run();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                setBackground(HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setBackground(NORMAL);
            }
        };
        addMouseListener(click);
        lines.addMouseListener(click);
        name.addMouseListener(click);
        text.addMouseListener(click);
        setComponentPopupMenu(menu);
        lines.setInheritsPopupMenu(true);
        name.setInheritsPopupMenu(true);
        text.setInheritsPopupMenu(true);
        east.setInheritsPopupMenu(true);
        play.setInheritsPopupMenu(true);
    }

    /** 가로로는 담는 열의 폭만큼, 세로로는 내용만큼. */
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, super.getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
    }
}
