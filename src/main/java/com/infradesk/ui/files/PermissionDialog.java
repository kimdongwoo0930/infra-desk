package com.infradesk.ui.files;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/** 서버 파일의 권한(rwx)을 고르는 창. 체크박스 9개와 8진수 입력(예: 644)이 서로 맞춰진다. */
final class PermissionDialog {

    /** @param recursive 폴더 안의 모든 항목에도 적용할지 */
    record Result(int mode, boolean recursive) {
    }

    private static final String[] ROWS = {"소유자", "그룹", "그 밖의 사용자"};
    private static final String[] COLUMNS = {"읽기", "쓰기", "실행"};

    private PermissionDialog() {
    }

    /**
     * @param initial        지금 권한(모르면 0644)
     * @param offerRecursive 폴더가 포함돼 있어 "하위 항목에도 적용"을 보여줄지
     * @return 고른 권한. 취소하면 null
     */
    static Result ask(Component parent, String subject, int initial, boolean offerRecursive) {
        JCheckBox[][] boxes = new JCheckBox[3][3];
        JTextField octal = new JTextField(4);
        octal.setFont(Theme.monoFont(13f));
        octal.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22");
        boolean[] syncing = {false};

        JPanel grid = new JPanel(new GridLayout(4, 4, 8, 4));
        grid.setOpaque(false);
        grid.add(new JLabel(""));
        for (String c : COLUMNS) {
            grid.add(new JLabel(c, SwingConstants.CENTER));
        }
        for (int r = 0; r < 3; r++) {
            grid.add(new JLabel(ROWS[r]));
            for (int c = 0; c < 3; c++) {
                JCheckBox box = new JCheckBox();
                box.setHorizontalAlignment(SwingConstants.CENTER);
                box.setOpaque(false);
                int bit = 1 << (8 - (r * 3 + c));
                box.setSelected((initial & bit) != 0);
                box.addActionListener(e -> {
                    if (!syncing[0]) {
                        syncing[0] = true;
                        octal.setText(String.format("%03o", modeOf(boxes)));
                        syncing[0] = false;
                    }
                });
                boxes[r][c] = box;
                grid.add(box);
            }
        }
        octal.setText(String.format("%03o", initial & 0777));
        octal.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                fromText();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                fromText();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                fromText();
            }

            private void fromText() {
                if (syncing[0]) {
                    return;
                }
                Integer mode = parse(octal.getText());
                if (mode == null) {
                    return;
                }
                syncing[0] = true;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        boxes[r][c].setSelected((mode & (1 << (8 - (r * 3 + c)))) != 0);
                    }
                }
                syncing[0] = false;
            }
        });

        JPanel number = new JPanel(new BorderLayout(8, 0));
        number.setOpaque(false);
        number.add(new JLabel("숫자로 입력"), BorderLayout.WEST);
        number.add(octal, BorderLayout.CENTER);

        JCheckBox recursive = new JCheckBox("폴더 안의 모든 항목에도 적용");
        recursive.setOpaque(false);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        body.add(new JLabel("<html>" + escape(subject) + "의 권한</html>"), BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout(0, 10));
        middle.setOpaque(false);
        middle.add(grid, BorderLayout.NORTH);
        middle.add(number, BorderLayout.CENTER);
        if (offerRecursive) {
            middle.add(recursive, BorderLayout.SOUTH);
        }
        body.add(middle, BorderLayout.CENTER);

        while (true) {
            int choice = JOptionPane.showConfirmDialog(parent, body, "권한 변경", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return null;
            }
            Integer mode = parse(octal.getText());
            if (mode != null) {
                return new Result(mode, offerRecursive && recursive.isSelected());
            }
            JOptionPane.showMessageDialog(parent, "권한은 000부터 777까지의 8진수로 입력해 주세요. 예: 644", "권한 변경",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static int modeOf(JCheckBox[][] boxes) {
        int mode = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (boxes[r][c].isSelected()) {
                    mode |= 1 << (8 - (r * 3 + c));
                }
            }
        }
        return mode;
    }

    /** "644" 같은 8진수 권한. 형식이 틀리면 null. */
    static Integer parse(String text) {
        String t = text.strip();
        if (!t.matches("[0-7]{3}")) {
            return null;
        }
        return Integer.parseInt(t, 8);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
