package com.infradesk.ui.containers;

import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;

/**
 * 컨테이너 로그를 별도 창으로 띄운다. 컨테이너 탭 아래의 로그 화면({@link ContainerLogView}) 전체를 그대로
 * 담는다: 컨테이너 전환 칩, 전체/경고/오류, 검색, 자동 새로고침, 줄 수, 줄바꿈이 모두 있다.
 *
 * <p>창마다 {@code ContainerLogView}를 따로 가져서 선택과 거르기가 서로 독립이다(메인 화면에서 nginx를 보면서
 * 이 창에서 mysql을 볼 수 있다). 모달이 아니라서 여러 개를 나란히 열 수 있다.
 *
 * <p>이 클래스는 창 틀만 맡는다. 화면에 넣을 {@link ContainerLogView}를 만들고 로그를 읽어 주는 일은 만든 쪽이 한다.
 */
public class ContainerLogWindow extends JDialog {

    public ContainerLogWindow(Window owner, String serverName, ContainerLogView view) {
        super(owner, "로그 · " + serverName, ModalityType.MODELESS);
        view.setOpenWindowVisible(false);
        getContentPane().setBackground(Theme.APP_BG);
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        getContentPane().add(view, BorderLayout.CENTER);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(760, 420));
        setSize(1100, 680);
        setLocationRelativeTo(owner);
        // 창을 닫으면 이 창의 자동 새로고침 타이머도 멈춘다.
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                view.close();
            }
        });
    }
}
