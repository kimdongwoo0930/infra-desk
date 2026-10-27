package com.infradesk.ui.components;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JComponent;

/**
 * 아직 기능이 없는 화면 요소를 "준비 중"으로 만드는 도구 (디자인 v2, docs/design/v2/GAPS.md).
 *
 * <p>규칙은 세 가지다.
 * <ol>
 *   <li>눌러도 아무 일도 하지 않는다 — 비활성({@code setEnabled(false)})으로 만든다.</li>
 *   <li>동작하는 척하지 않는다 — 특히 읽기 전용 스위치처럼 안전과 관련된 것은 켜질 수 없어야 한다.</li>
 *   <li>마우스를 올리면 "준비 중이에요"라고 알려 준다.</li>
 * </ol>
 *
 * <p>사용하는 곳에는 {@code // 준비 중(v2):} 주석을 붙인다. 기능을 만들 때 이 호출만 지우면 되고,
 * 나중에 {@code grep "준비 중(v2)"}로 한 번에 찾을 수 있다.
 */
public final class ComingSoon {

    /** 마우스를 올렸을 때 보이는 안내. */
    public static final String TOOLTIP = "준비 중이에요";

    private ComingSoon() {
    }

    /**
     * 컴포넌트와 그 안의 모든 자식을 비활성으로 만들고 안내 문구를 붙인다.
     * 패널을 비활성으로 만들어도 자식 버튼은 그대로 눌리기 때문에 자식까지 내려가며 처리한다.
     *
     * @return 받은 컴포넌트 그대로 (한 줄로 이어 쓰기 위해)
     */
    public static <T extends JComponent> T apply(T component) {
        component.setEnabled(false);
        // 비활성 컴포넌트도 Swing은 툴팁을 보여 준다.
        component.setToolTipText(TOOLTIP);
        for (Component child : component.getComponents()) {
            if (child instanceof JComponent c) {
                apply(c);
            } else if (child instanceof Container) {
                child.setEnabled(false);
            }
        }
        return component;
    }

    /** "준비 중" 글자가 적힌 회색 알약. 탭 이름 옆처럼 자리만 보여 줄 때 쓴다. */
    public static StatusBadge badge() {
        StatusBadge badge = new StatusBadge();
        badge.setKind(StatusBadge.Kind.NEUTRAL, "준비 중");
        return badge;
    }
}
