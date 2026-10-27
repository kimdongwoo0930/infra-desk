package com.infradesk.ui.containers;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/**
 * 서버 화면의 "컨테이너" 탭 본문 (디자인 v2): 위에 컨테이너 표({@link ContainersPanel}), 아래에 로그
 * ({@link ContainerLogView}). 가운데 손잡이를 끌어 위아래 높이를 바꾼다.
 *
 * <p>예전에는 로그가 컨테이너 하나의 텍스트만 보여 주는 별도 창으로만 열렸다. 이제 표 아래에서 바로 보고, 창이
 * 필요하면 로그 패널의 [별도 창] 버튼으로 이 로그 화면 전체를 새 창에 하나 더 연다({@link ContainerLogWindow}).
 *
 * <p>나눈 위치는 앱을 켜 둔 동안만 기억한다(저장하지 않는다). 저장하려면 설정 저장소가 필요해서 뒤로 미뤘다.
 * 사용자가 손잡이를 끌기 전까지는 표가 컨테이너 줄 수만큼의 높이를 갖도록 손잡이를 자동으로 맞춘다({@link #fitTable}).
 */
public class ContainersTab extends JPanel {

    private final ContainersPanel containers = new ContainersPanel();
    private final ContainerLogView logs = new ContainerLogView();
    private final JSplitPane split;
    /** 사용자가 손잡이를 끌었으면 true. 그 뒤로는 자동으로 위치를 바꾸지 않는다. */
    private boolean userMovedDivider;

    public ContainersTab() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 28, 24, 28));

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, containers, logs);
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(12);
        // 창이 커지거나 작아지면 남는 높이는 로그가 가진다(표는 줄 수만큼이면 충분하다).
        split.setResizeWeight(0.0);
        split.setContinuousLayout(true);
        // "사용자가 끌었다"는 손잡이를 누른 것으로 안다. 위치가 바뀐 것만 보면, 처음 배치할 때 Swing이 스스로
        // 위치를 정하는 것도 끈 것으로 잘못 알게 된다.
        if (split.getUI() instanceof BasicSplitPaneUI ui) {
            ui.getDivider().addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    userMovedDivider = true;
                }
            });
        }
        add(split, BorderLayout.CENTER);
    }

    /**
     * 표가 컨테이너 줄 수만큼의 높이를 갖도록 손잡이를 맞춘다. 로그는 최소한 전체 높이의 40%를 가진다.
     * 사용자가 이미 손잡이를 끌었으면 그대로 둔다.
     *
     * <p>손잡이의 처음 위치는 탭을 처음 열 때(표에 아직 데이터가 없어 낮을 때)의 높이로 정해지고 이후
     * 저절로 바뀌지 않는다. 그래서 목록이 도착하거나 탭을 열 때마다 이것을 불러 준다. 배치가 끝난 뒤의
     * 크기가 필요해서 한 박자 늦춰 실행한다.
     */
    public void fitTable() {
        if (userMovedDivider) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            int wanted = containers.getPreferredSize().height;
            int available = split.getHeight() - split.getDividerSize();
            int limit = available > 0 ? (int) (available * 0.6) : wanted;
            split.setDividerLocation(Math.min(wanted, limit));
        });
    }

    /** 위쪽 컨테이너 표. */
    public ContainersPanel containers() {
        return containers;
    }

    /** 아래쪽 로그 패널. */
    public ContainerLogView logs() {
        return logs;
    }
}
