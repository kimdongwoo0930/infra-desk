package com.infradesk.ui;

import com.infradesk.service.AccessModeService;
import com.infradesk.service.AdminPasswordService;
import com.infradesk.service.AdminPasswordService.Check;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.WrappingLabel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 관리 비밀번호 창 세 가지. 비밀번호 확인(PBKDF2 해시)은 시간이 걸리므로 화면을 멈추지 않게 따로 돌리고, 결과만 화면으로 돌려받는다.
 *
 * <ul>
 *   <li>{@link #ask}: 관리 모드로 바꿀 때 비밀번호를 묻는다.
 *   <li>{@link #setUp}: 처음 비밀번호를 정한다. 정하기 전에는 앱을 쓸 수 없어서 닫을 수 없고, [종료]만 있다.
 *   <li>{@link #change}: 현재 비밀번호를 확인하고 새 비밀번호로 바꾼다.
 * </ul>
 */
final class PasswordDialog extends JDialog {

    private enum Kind { ASK, SETUP, CHANGE }

    private final Kind kind;
    private final AccessModeService access;
    private final AdminPasswordService passwords;
    private final JPasswordField current = new JPasswordField();
    private final JPasswordField next = new JPasswordField();
    private final JPasswordField confirm = new JPasswordField();
    private final JLabel error = new JLabel(" ");
    private final JButton ok;
    private final JButton cancel;
    private boolean succeeded;

    private PasswordDialog(Window owner, Kind kind, AccessModeService access, AdminPasswordService passwords, String hint) {
        super(owner, title(kind), ModalityType.APPLICATION_MODAL);
        this.kind = kind;
        this.access = access;
        this.passwords = passwords;
        this.ok = Buttons.primary(kind == Kind.ASK ? "관리 모드로" : kind == Kind.SETUP ? "정하기" : "바꾸기", null);
        this.cancel = Buttons.secondary(kind == Kind.SETUP ? "앱 종료" : "취소", null);

        current.getAccessibleContext().setAccessibleName(kind == Kind.CHANGE ? "현재 비밀번호" : "비밀번호");
        next.getAccessibleContext().setAccessibleName("새 비밀번호");
        confirm.getAccessibleContext().setAccessibleName("새 비밀번호 확인");

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 12, 22));
        body.add(new WrappingLabel(description(kind) + (hint == null ? "" : "\n" + hint), 376, -1, Theme.TEXT_SECONDARY));
        body.add(Box.createVerticalStrut(14));
        switch (kind) {
            case ASK -> body.add(labeled("비밀번호", current));
            case SETUP -> {
                body.add(labeled("새 비밀번호 (" + AdminPasswordService.MIN_LENGTH + "자 이상)", next));
                body.add(Box.createVerticalStrut(10));
                body.add(labeled("한 번 더", confirm));
            }
            case CHANGE -> {
                body.add(labeled("현재 비밀번호", current));
                body.add(Box.createVerticalStrut(10));
                body.add(labeled("새 비밀번호 (" + AdminPasswordService.MIN_LENGTH + "자 이상)", next));
                body.add(Box.createVerticalStrut(10));
                body.add(labeled("한 번 더", confirm));
            }
        }
        error.setForeground(Theme.DANGER_TEXT);
        error.setAlignmentX(LEFT_ALIGNMENT);
        error.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        body.add(error);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(0, 22, 18, 22));
        footer.add(cancel);
        footer.add(ok);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(body, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        ok.addActionListener(e -> submit());
        cancel.addActionListener(e -> dispose());
        // 처음 정하는 창은 닫을 수 없다(정하기 전에는 앱을 쓸 수 없다). 창을 닫으려는 것은 [앱 종료]와 같다.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });
        if (kind != Kind.SETUP) {
            getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
        pack();
        setSize(430, getHeight());
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    // ── 바깥에서 쓰는 곳 ────────────────────────────────────────

    /**
     * 관리 모드로 바꿀 비밀번호를 묻는다.
     *
     * @param hint 데모 모드의 안내처럼 덧붙일 글(없으면 null)
     * @return 비밀번호가 맞아서 관리 모드로 바뀌었으면 true
     */
    static boolean ask(Component owner, AccessModeService access, String hint) {
        return show(new PasswordDialog(window(owner), Kind.ASK, access, null, hint));
    }

    /**
     * 처음 비밀번호를 정한다. 정할 때까지 앱을 쓸 수 없으므로 닫을 수 없다.
     *
     * @return 비밀번호를 정했으면 true, [앱 종료]를 눌렀으면 false
     */
    static boolean setUp(Component owner, AdminPasswordService passwords, String hint) {
        return show(new PasswordDialog(window(owner), Kind.SETUP, null, passwords, hint));
    }

    /** 비밀번호를 바꾼다. 바꿨으면 true. */
    static boolean change(Component owner, AdminPasswordService passwords) {
        return show(new PasswordDialog(window(owner), Kind.CHANGE, null, passwords, null));
    }

    private static boolean show(PasswordDialog dialog) {
        dialog.setVisible(true);   // 닫힐 때까지 기다린다(모달)
        return dialog.succeeded;
    }

    private static Window window(Component c) {
        return c == null ? null : SwingUtilities.getWindowAncestor(c) != null ? SwingUtilities.getWindowAncestor(c)
                : c instanceof Window w ? w : null;
    }

    // ── 동작 ────────────────────────────────────────────────────

    private void submit() {
        String problem = problem();
        if (problem != null) {
            error.setText(problem);
            return;
        }
        setBusy(true);
        char[] typed = current.getPassword();
        char[] fresh = next.getPassword();
        switch (kind) {
            case ASK -> Async.run(() -> access.switchToManage(typed), this::afterCheck, this::failed);
            case SETUP -> Async.run(() -> {
                passwords.set(fresh);
                return Check.OK;
            }, this::afterCheck, this::failed);
            case CHANGE -> Async.run(() -> passwords.change(typed, fresh), this::afterCheck, this::failed);
        }
    }

    /** 입력만 보고 알 수 있는 문제(비었음, 짧음, 두 칸이 다름). 문제가 없으면 null. */
    private String problem() {
        if (kind != Kind.SETUP && current.getPassword().length == 0) {
            return "비밀번호를 입력하세요.";
        }
        if (kind != Kind.ASK) {
            if (next.getPassword().length < AdminPasswordService.MIN_LENGTH) {
                return "새 비밀번호는 " + AdminPasswordService.MIN_LENGTH + "자 이상이어야 해요.";
            }
            if (!java.util.Arrays.equals(next.getPassword(), confirm.getPassword())) {
                return "두 번 입력한 비밀번호가 달라요.";
            }
        }
        return null;
    }

    private void afterCheck(Check check) {
        switch (check) {
            case OK -> {
                succeeded = true;
                wipeFields();
                dispose();
            }
            case WRONG -> {
                setBusy(false);
                error.setText(kind == Kind.CHANGE ? "현재 비밀번호가 맞지 않아요." : "비밀번호가 맞지 않아요.");
                current.setText("");
                current.requestFocusInWindow();
            }
            case LOCKED -> {
                setBusy(false);
                error.setText("너무 많이 틀려서 잠시 막았어요. 잠시 뒤에 다시 해 보세요.");
                current.setText("");
            }
            case NOT_SET -> {
                setBusy(false);
                error.setText("아직 관리 비밀번호가 없어요.");
            }
        }
    }

    private void failed(Throwable t) {
        setBusy(false);
        error.setText(Async.message(t));
    }

    private void setBusy(boolean busy) {
        ok.setEnabled(!busy);
        cancel.setEnabled(!busy);
        if (busy) {
            error.setText(" ");
        }
    }

    private void wipeFields() {
        current.setText("");
        next.setText("");
        confirm.setText("");
    }

    // ── 글 ──────────────────────────────────────────────────────

    private static String title(Kind kind) {
        return switch (kind) {
            case ASK -> "관리 모드로 바꾸기";
            case SETUP -> "관리 비밀번호 정하기";
            case CHANGE -> "관리 비밀번호 바꾸기";
        };
    }

    private static String description(Kind kind) {
        return switch (kind) {
            case ASK -> "서버를 바꾸거나 접속하는 기능이 열려요. 관리 비밀번호를 입력하세요.";
            case SETUP -> "서버를 실수로 바꾸지 않도록, 읽기 모드에서 관리 모드로 바꿀 때 이 비밀번호를 물어요. "
                    + "정해야 앱을 쓸 수 있어요. 비밀번호는 되돌릴 수 없는 형태로만 저장돼요. "
                    + "잊으면 앱을 완전히 종료한 뒤 --reset-admin-password 옵션으로 실행해 지우고 다시 정하세요(자세한 방법은 설명서).";
            case CHANGE -> "현재 비밀번호를 확인한 뒤 새 비밀번호로 바꿔요.";
        };
    }

    private static JPanel labeled(String label, JPasswordField field) {
        JLabel l = new JLabel(label);
        l.setForeground(Theme.TEXT_SECONDARY);
        field.setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.add(l, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }
}
