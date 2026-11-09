package com.infradesk.ui.files;

import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.undo.UndoManager;

/**
 * 서버 파일을 앱 안에서 열어 고치는 창. 파일을 읽고 올리는 일은 모르고(그건 {@link Saver}를 구현한 {@link FilesTab}이
 * 한다), 글자 편집·변경 표시·저장 요청·닫을 때 확인만 맡는다. 모달이 아니라서 여러 파일을 나란히 열 수 있다.
 *
 * <p>저장하면 전송 목록에 줄을 만들지 않고 이 창의 상태 줄에만 결과가 나온다. 고친 내용이 있는 채로 닫으면
 * "저장하시겠습니까?"를 묻고 {@link CloseChoice}대로 처리한다(X로 닫으면 저장 안 함).
 */
final class FileEditorWindow extends JDialog {

    /** 저장 결과. */
    record SaveResult(boolean ok, boolean cancelled, String message) {
        static SaveResult saved() {
            return new SaveResult(true, false, "");
        }

        /** 사용자가 서버 파일 덮어쓰기를 거절했다. */
        static SaveResult declined() {
            return new SaveResult(false, true, "");
        }

        static SaveResult failed(String message) {
            return new SaveResult(false, false, message);
        }
    }

    /** 글자를 서버에 올려 주는 쪽. 끝나면 화면 스레드에서 {@code done}을 부른다. */
    interface Saver {
        void save(FileEditorWindow window, byte[] content, Consumer<SaveResult> done);
    }

    private static final String MOD = FilePane.IS_MAC ? "meta" : "ctrl";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String fileName;
    private final String serverName;
    private final String path;
    private final TextFileCodec.Decoded doc;
    private final Saver saver;
    private final JTextArea area = new JTextArea();
    private final JButton save = Buttons.primary("저장  " + (FilePane.IS_MAC ? "⌘S" : "Ctrl+S"), null);
    private final JLabel status = new JLabel(" ");
    private final JLabel position = new JLabel(" ");
    private final UndoManager undo = new UndoManager();

    private boolean dirty;
    /** 글자가 바뀔 때마다 늘어난다. 저장하는 동안 또 고쳤는지 알아보는 데 쓴다. */
    private int generation;
    private boolean saving;
    private boolean readOnly;
    /** 저장이 끝나면 이어서 할 일(닫기 확인에서 [저장]을 골랐을 때 닫기). */
    private Runnable afterSave;
    private Runnable onClosed = () -> { };
    private boolean disposed;
    private java.util.function.IntSupplier closePrompt = this::askClose;

    FileEditorWindow(Window owner, String serverName, String path, TextFileCodec.Decoded doc, Saver saver) {
        super(owner, "", ModalityType.MODELESS);
        this.serverName = serverName;
        this.path = path;
        this.fileName = path.substring(path.lastIndexOf('/') + 1);
        this.doc = doc;
        this.saver = saver;

        area.setText(doc.text());
        area.setCaretPosition(0);
        area.setFont(Theme.monoFont(13f));
        area.setBackground(Theme.PANEL_BG);
        area.setForeground(Theme.TEXT);
        area.setCaretColor(Theme.TEXT);
        area.setSelectionColor(Theme.SELECTION_BG);
        area.setTabSize(4);
        area.setMargin(new Insets(10, 12, 10, 12));
        // 처음 글자를 넣은 것은 되돌리기와 "고침"에 들어가지 않으므로 리스너는 그 뒤에 단다.
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        });
        area.getDocument().addUndoableEditListener(undo);
        area.addCaretListener(e -> showPosition());
        bindKeys();

        JLabel pathLabel = new JLabel(path);
        pathLabel.setFont(Theme.monoFont(12f));
        pathLabel.setForeground(Theme.TEXT_SECONDARY);
        status.setForeground(Theme.TEXT_MUTED);
        save.addActionListener(e -> saveThen(null));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(status);
        right.add(save);
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(Theme.APP_BG);
        header.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        header.add(pathLabel, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.DIVIDER));
        scroll.getViewport().setBackground(Theme.PANEL_BG);

        position.setForeground(Theme.TEXT_MUTED);
        JLabel format = new JLabel(formatText(doc));
        format.setForeground(doc.mixedEndings() ? Theme.WARNING : Theme.TEXT_MUTED);
        format.setToolTipText(doc.mixedEndings() ? "줄바꿈이 LF와 CRLF로 섞여 있어요. 저장하면 하나로 통일돼요." : null);
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.setBackground(Theme.APP_BG);
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        bottom.add(position, BorderLayout.WEST);
        bottom.add(format, BorderLayout.EAST);

        getContentPane().setBackground(Theme.APP_BG);
        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(520, 320));
        setSize(900, 640);
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        refreshTitle();
        showPosition();
    }

    /** 이 화면이 닫힌 뒤(저장 안 함 포함) 불린다. 파일 탭이 열린 창 목록에서 뺀다. */
    void onClosed(Runnable listener) {
        this.onClosed = listener;
    }

    @Override
    public void dispose() {
        super.dispose();
        if (!disposed) {
            disposed = true;
            onClosed.run();
        }
    }

    /** 시험용: 닫혔는지. */
    boolean isDisposed() {
        return disposed;
    }

    /** 시험용: 편집 중인 글자 칸. */
    JTextArea textArea() {
        return area;
    }

    /** 시험용: 오른쪽 위 상태 글자. */
    String statusText() {
        return status.getText();
    }

    /** 시험용: 닫을 때 묻는 창을 대신할 답(CloseChoice 버튼의 번호, X로 닫으면 -1)을 정한다. */
    void setClosePrompt(java.util.function.IntSupplier prompt) {
        this.closePrompt = prompt;
    }

    String path() {
        return path;
    }

    /** 저장하지 않은 고침이 있는지. */
    boolean isDirty() {
        return dirty;
    }

    /** 읽기 모드가 되면 더 고치거나 저장하지 못하게 한다. */
    void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        area.setEditable(!readOnly);
        save.setEnabled(!readOnly && !saving);
        if (readOnly) {
            showStatus("읽기 모드라 저장할 수 없어요", Theme.WARNING);
        }
    }

    /** 묻지 않고 닫는다(앱을 끌 때, 서버를 지울 때). 저장하지 않은 내용은 사라진다. */
    void closeWithoutAsking() {
        dispose();
    }

    // ---- 저장과 닫기 ----

    private void saveThen(Runnable afterOk) {
        if (readOnly) {
            return;
        }
        if (saving) {
            if (afterOk != null) {
                afterSave = afterOk;
            }
            return;
        }
        if (!dirty) {
            if (afterOk != null) {
                afterOk.run();
            }
            return;
        }
        saving = true;
        afterSave = afterOk;
        save.setEnabled(false);
        showStatus("저장하는 중…", Theme.TEXT_MUTED);
        int savedGeneration = generation;
        byte[] bytes = TextFileCodec.encode(area.getText(), doc.crlf(), doc.bom());
        saver.save(this, bytes, result -> {
            saving = false;
            save.setEnabled(!readOnly);
            Runnable next = afterSave;
            afterSave = null;
            if (result.ok()) {
                if (generation == savedGeneration) {
                    setDirty(false);   // 저장하는 사이 또 고쳤다면 그 고침은 아직 저장 전이다
                }
                showStatus("저장했어요 · " + LocalTime.now().format(TIME), Theme.TEXT_MUTED);
                if (next != null && !dirty) {
                    next.run();
                }
            } else if (result.cancelled()) {
                showStatus("저장하지 않았어요", Theme.WARNING);
            } else {
                showStatus("저장하지 못했어요: " + result.message(), Theme.DANGER_TEXT);
            }
        });
    }

    /** 창의 X나 닫기 요청. 고친 내용이 있으면 [저장] [저장 안 함] [취소]를 묻는다(X로 닫으면 저장 안 함). */
    void requestClose() {
        if (!dirty) {
            dispose();
            return;
        }
        switch (CloseChoice.fromOption(closePrompt.getAsInt())) {
            case SAVE -> saveThen(this::dispose);
            case DISCARD -> dispose();
            case CANCEL -> { }
        }
    }

    private int askClose() {
        return JOptionPane.showOptionDialog(this, "'" + fileName + "'의 고친 내용을 저장할까요?", "저장하시겠습니까?",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, CloseChoice.BUTTONS, CloseChoice.BUTTONS[0]);
    }

    // ---- 표시 ----

    private void edited() {
        generation++;
        setDirty(true);
    }

    private void setDirty(boolean dirty) {
        this.dirty = dirty;
        refreshTitle();
        if (dirty && !saving) {
            showStatus("수정됨", Theme.WARNING);
        }
    }

    private void refreshTitle() {
        setTitle((dirty ? "● " : "") + fileName + " — " + serverName);
        getRootPane().putClientProperty("Window.documentModified", dirty);   // macOS 제목 줄의 점
    }

    private void showStatus(String text, java.awt.Color color) {
        status.setText(text);
        status.setForeground(color);
    }

    private void showPosition() {
        try {
            int caret = area.getCaretPosition();
            int line = area.getLineOfOffset(caret);
            position.setText("줄 " + (line + 1) + ", 열 " + (caret - area.getLineStartOffset(line) + 1));
        } catch (BadLocationException e) {
            position.setText(" ");
        }
    }

    static String formatText(TextFileCodec.Decoded doc) {
        return "UTF-8" + (doc.bom() ? " (BOM)" : "") + " · " + (doc.crlf() ? "CRLF" : "LF")
                + (doc.mixedEndings() ? " · 줄바꿈이 섞여 있어요" : "");
    }

    private void bindKeys() {
        bind(area, MOD + " Z", "undo", () -> {
            if (undo.canUndo()) {
                undo.undo();
            }
        });
        bind(area, MOD + (FilePane.IS_MAC ? " shift Z" : " Y"), "redo", () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        });
        bind(getRootPane(), MOD + " S", "save-file", () -> saveThen(null));
    }

    private static void bind(JComponent c, String keyStroke, String name, Runnable action) {
        int condition = c instanceof JTextArea ? JComponent.WHEN_FOCUSED : JComponent.WHEN_IN_FOCUSED_WINDOW;
        c.getInputMap(condition).put(KeyStroke.getKeyStroke(keyStroke), name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
