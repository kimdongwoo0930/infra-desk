package com.infradesk.ui.containers;

import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * 컨테이너 표의 행 끝에 놓는 작업 아이콘들(디자인 v2): 어떤 컨테이너에 무엇이 보이는지, 칸 안의 어디에
 * 놓이는지, 눌린 위치가 어느 아이콘인지.
 *
 * <p>표 칸은 진짜 버튼을 넣을 수 없어서(Swing 표는 칸을 그림으로만 그린다) 아이콘을 직접 그리고, 마우스가
 * 눌린 좌표로 어느 아이콘인지 계산한다. 그 계산을 화면 코드와 떼어 두면 그림 없이 테스트할 수 있다.
 */
final class ContainerRowActions {

    private ContainerRowActions() {
    }

    /** 행에서 할 수 있는 작업. */
    enum RowAction {
        SHELL("셸 열기", "terminal"),
        CONSOLE("DB 콘솔 열기", "database"),
        RESTART("재시작", "refresh"),
        STOP("정지", "stop"),
        START("시작", "play");

        final String label;
        final String icon;

        RowAction(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    /** 아이콘 하나의 눌리는 영역 크기. */
    static final int ICON_BOX = 28;
    static final int GAP = 4;
    private static final int RIGHT_PAD = 12;

    /**
     * 이 컨테이너에 보이는 작업을 왼쪽부터 순서대로. 실행 중이면 셸, (DB 이미지면) 콘솔, 재시작, 정지.
     * 멈췄으면 시작 하나뿐이다(멈춘 컨테이너 안으로는 들어갈 수 없다).
     *
     * <p>로그 보기는 아이콘이 없다: 행을 클릭하면 그 컨테이너의 로그가 아래에 뜬다.
     */
    static List<RowAction> actionsFor(Container c) {
        List<RowAction> actions = new ArrayList<>();
        if (c.isRunning()) {
            actions.add(RowAction.SHELL);
            if (DockerCommands.Console.forImage(c.image()).isPresent()) {
                actions.add(RowAction.CONSOLE);
            }
            actions.add(RowAction.RESTART);
            actions.add(RowAction.STOP);
        } else {
            actions.add(RowAction.START);
        }
        return actions;
    }

    /**
     * 칸 안에서 각 작업이 차지하는 영역. 오른쪽 끝에 붙여서 왼쪽으로 놓는다(그래서 DB 콘솔이 있는 행은
     * 아이콘이 하나 더 왼쪽으로 늘어난다). 결과는 {@code actions}와 같은 순서다.
     */
    static List<Rectangle> boxes(List<RowAction> actions, int cellWidth, int cellHeight) {
        Rectangle[] boxes = new Rectangle[actions.size()];
        int right = cellWidth - RIGHT_PAD;
        for (int i = actions.size() - 1; i >= 0; i--) {
            boxes[i] = new Rectangle(right - ICON_BOX, (cellHeight - ICON_BOX) / 2, ICON_BOX, ICON_BOX);
            right -= ICON_BOX + GAP;
        }
        return List.of(boxes);
    }

    /** 칸 안의 점 {@code (x, y)}가 누른 작업. 아이콘이 아닌 곳이면 null. */
    static RowAction hit(Container c, int x, int y, int cellWidth, int cellHeight) {
        List<RowAction> actions = actionsFor(c);
        List<Rectangle> boxes = boxes(actions, cellWidth, cellHeight);
        for (int i = 0; i < actions.size(); i++) {
            if (boxes.get(i).contains(x, y)) {
                return actions.get(i);
            }
        }
        return null;
    }
}
