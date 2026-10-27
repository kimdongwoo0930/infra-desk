package com.infradesk.ui.containers;

import com.infradesk.ssh.Container;
import com.infradesk.ui.containers.ContainerRowActions.RowAction;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerRowActionsTest {

    private static Container container(String image, boolean running) {
        return new Container("a".repeat(64), "name", image, running ? "running" : "exited", "", "", -1, "", -1, "");
    }

    @Test
    void 실행_중인_컨테이너는_셸_재시작_정지다() {
        assertEquals(List.of(RowAction.SHELL, RowAction.RESTART, RowAction.STOP),
                ContainerRowActions.actionsFor(container("nginx:1.27", true)));
    }

    @Test
    void 데이터베이스_이미지는_콘솔이_셸_다음에_더_보인다() {
        assertEquals(List.of(RowAction.SHELL, RowAction.CONSOLE, RowAction.RESTART, RowAction.STOP),
                ContainerRowActions.actionsFor(container("mysql:8.4", true)));
    }

    @Test
    void 멈춘_컨테이너는_시작_하나뿐이다() {
        assertEquals(List.of(RowAction.START),
                ContainerRowActions.actionsFor(container("nginx:1.27", false)));
        // DB 이미지라도 멈췄으면 콘솔은 없다
        assertEquals(List.of(RowAction.START),
                ContainerRowActions.actionsFor(container("mysql:8.4", false)));
    }

    @Test
    void 아이콘은_오른쪽_끝에_붙고_서로_겹치지_않는다() {
        List<RowAction> actions = ContainerRowActions.actionsFor(container("nginx:1.27", true));
        List<Rectangle> boxes = ContainerRowActions.boxes(actions, 300, 40);

        assertEquals(actions.size(), boxes.size());
        // 마지막 아이콘의 오른쪽 끝은 칸 끝에서 오른쪽 여백(12)만큼 떨어져 있다.
        assertEquals(300 - 12, boxes.getLast().x + boxes.getLast().width);
        for (int i = 1; i < boxes.size(); i++) {
            assertTrue(boxes.get(i - 1).x + boxes.get(i - 1).width <= boxes.get(i).x, "겹치면 안 된다");
        }
        // 세로 가운데
        assertEquals((40 - ContainerRowActions.ICON_BOX) / 2, boxes.getFirst().y);
    }

    @Test
    void DB_콘솔이_있는_행은_아이콘이_하나_더_왼쪽으로_늘어난다() {
        Rectangle plainFirst = ContainerRowActions.boxes(
                ContainerRowActions.actionsFor(container("nginx:1.27", true)), 300, 40).getFirst();
        Rectangle dbFirst = ContainerRowActions.boxes(
                ContainerRowActions.actionsFor(container("mysql:8.4", true)), 300, 40).getFirst();

        assertEquals(plainFirst.x - (ContainerRowActions.ICON_BOX + 4), dbFirst.x);
    }

    @Test
    void 누른_위치로_어느_작업인지_알아낸다() {
        Container nginx = container("nginx:1.27", true);
        List<RowAction> actions = ContainerRowActions.actionsFor(nginx);
        List<Rectangle> boxes = ContainerRowActions.boxes(actions, 300, 40);

        for (int i = 0; i < actions.size(); i++) {
            Rectangle b = boxes.get(i);
            assertEquals(actions.get(i), ContainerRowActions.hit(nginx, b.x + b.width / 2, b.y + b.height / 2, 300, 40));
        }
        // 아이콘 사이의 틈, 칸의 왼쪽 빈 곳, 아이콘 위쪽 여백은 아무것도 아니다
        assertNull(ContainerRowActions.hit(nginx, 5, 20, 300, 40));
        Rectangle first = boxes.getFirst();
        assertNull(ContainerRowActions.hit(nginx, first.x + first.width / 2, 1, 300, 40));
    }

    @Test
    void 시작도_다른_아이콘과_같은_크기의_아이콘이다() {
        // 글자가 있는 알약 버튼이 아니라 삼각형 아이콘 하나다.
        List<Rectangle> boxes = ContainerRowActions.boxes(
                ContainerRowActions.actionsFor(container("nginx:1.27", false)), 300, 40);

        assertEquals(1, boxes.size());
        assertEquals(ContainerRowActions.ICON_BOX, boxes.getFirst().width);
        assertEquals(300 - 12, boxes.getFirst().x + boxes.getFirst().width);
    }
}
