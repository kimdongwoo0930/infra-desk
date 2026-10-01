package com.infradesk.app;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.im.InputContext;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 키보드, 포커스, 창 활성화 이벤트를 stderr에 출력한다. 직접 볼 수 없는 기기의 입력 문제를
 * 디버깅하기 위한 것이다. 입력한 글자는 절대 출력하지 않으며(필드에 OCID가 들어 있을 수 있다),
 * 문자와 숫자는 "a" / "9"로 표시하고 수정 키 조합과 특수 키만 이름으로 표시한다.
 * {@code --debug-input}으로 켠다.
 */
final class InputDiagnostics {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private InputDiagnostics() {
    }

    static void install() {
        long mask = AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK
                | AWTEvent.WINDOW_FOCUS_EVENT_MASK;
        Toolkit.getDefaultToolkit().addAWTEventListener(InputDiagnostics::log, mask);
        log("input diagnostics on · java " + System.getProperty("java.version")
                + " · os " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
    }

    private static void log(AWTEvent event) {
        switch (event) {
            case KeyEvent k when k.getID() == KeyEvent.KEY_PRESSED -> log("KEY_PRESSED  " + keyText(k) + " consumed=" + k.isConsumed()
                    + " → " + describe(k.getComponent()) + " | ime=" + inputLocale(k.getComponent()));
            case KeyEvent k when k.getID() == KeyEvent.KEY_TYPED -> log("KEY_TYPED    " + charClass(k.getKeyChar())
                    + " → " + describe(k.getComponent()));
            case MouseEvent m when m.getID() == MouseEvent.MOUSE_PRESSED -> log("MOUSE_PRESS  button=" + m.getButton()
                    + " popup=" + m.isPopupTrigger() + " → " + describe(m.getComponent())
                    + " | focusOwner=" + describe(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
            case FocusEvent f when f.getID() == FocusEvent.FOCUS_GAINED -> log("FOCUS_GAINED " + describe(f.getComponent())
                    + " cause=" + f.getCause());
            case WindowEvent w when w.getID() == WindowEvent.WINDOW_GAINED_FOCUS -> log("WINDOW_FOCUS " + describe(w.getWindow()));
            case WindowEvent w when w.getID() == WindowEvent.WINDOW_LOST_FOCUS -> log("WINDOW_LOST  " + describe(w.getWindow())
                    + " → " + describe(w.getOppositeWindow()));
            default -> { }
        }
    }

    private static String keyText(KeyEvent k) {
        String mods = KeyEvent.getModifiersExText(k.getModifiersEx());
        int code = k.getKeyCode();
        boolean plainChar = mods.isEmpty() && (Character.isLetterOrDigit(code) || code == KeyEvent.VK_PERIOD);
        String key = plainChar ? "<char>" : KeyEvent.getKeyText(code);
        return (mods.isEmpty() ? "" : mods + "+") + key + " (code " + (plainChar ? "?" : code) + ")";
    }

    private static String charClass(char c) {
        if (Character.isLetter(c)) {
            return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ? "<hangul>" : "<letter>";
        }
        return Character.isDigit(c) ? "<digit>" : c < 0x20 ? "<ctrl " + (int) c + ">" : "<symbol>";
    }

    private static String inputLocale(Component c) {
        if (c == null) {
            return "?";
        }
        InputContext ic = c.getInputContext();
        return ic == null || ic.getLocale() == null ? "none" : ic.getLocale().toString();
    }

    private static String describe(Component c) {
        if (c == null) {
            return "null";
        }
        String name = c.getClass().getSimpleName().isEmpty() ? c.getClass().getName() : c.getClass().getSimpleName();
        String a11y = c.getAccessibleContext() == null ? null : c.getAccessibleContext().getAccessibleName();
        Window w = c instanceof Window win ? win : javax.swing.SwingUtilities.getWindowAncestor(c);
        String window = w == null ? "" : " in " + w.getClass().getSimpleName()
                + (w.isActive() ? "(active)" : "(inactive)") + (w.isFocused() ? "(focused)" : "");
        return name + (a11y == null ? "" : "[" + a11y + "]") + window;
    }

    private static void log(String line) {
        System.err.println("[input " + LocalTime.now().format(TIME) + "] " + line);
    }
}
