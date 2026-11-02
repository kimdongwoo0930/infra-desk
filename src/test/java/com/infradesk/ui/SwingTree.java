package com.infradesk.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JLabel;

/** 시험용: 화면에 실제로 보이는 부품을 모은다(숨겨진 부품과 숨겨진 부품 안의 것은 뺀다). */
public final class SwingTree {

    private SwingTree() {
    }

    /** 보이는 버튼의 글자(글자가 없는 아이콘 버튼은 안내 글자). */
    public static List<String> buttons(Component root) {
        List<String> out = new ArrayList<>();
        collect(root, AbstractButton.class, b -> out.add(b.getText() == null || b.getText().isEmpty()
                ? String.valueOf(b.getToolTipText()) : b.getText()));
        return out;
    }

    /** 보이는 라벨의 글자. */
    public static List<String> labels(Component root) {
        List<String> out = new ArrayList<>();
        collect(root, JLabel.class, l -> out.add(l.getText()));
        return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, java.util.function.Consumer<T> sink) {
        if (!c.isVisible()) {
            return;
        }
        if (type.isInstance(c)) {
            sink.accept(type.cast(c));
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, type, sink);
            }
        }
    }
}
