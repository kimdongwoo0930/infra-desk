package com.infradesk.ui.files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 편집 창의 변경 표시, 저장, 닫을 때 확인. 창은 화면에 띄우지 않고 만들기만 한다(화면이 없는 환경에서는 건너뛴다). */
class FileEditorWindowTest {

    /** 저장 요청을 모아 두고, 시험이 결과를 직접 알려 준다. */
    private static final class FakeSaver implements FileEditorWindow.Saver {
        final List<byte[]> saved = new ArrayList<>();
        final List<Consumer<FileEditorWindow.SaveResult>> pending = new ArrayList<>();

        @Override
        public void save(FileEditorWindow window, byte[] content, Consumer<FileEditorWindow.SaveResult> done) {
            saved.add(content);
            pending.add(done);
        }

        void finish(FileEditorWindow.SaveResult result) {
            pending.removeFirst().accept(result);
        }
    }

    private FileEditorWindow window;
    private FakeSaver saver;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "창을 만들 수 없는 환경");
        saver = new FakeSaver();
        onEdt(() -> window = create("a\nb\n", false, false));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (window != null) {
            onEdt(window::closeWithoutAsking);
        }
    }

    private FileEditorWindow create(String text, boolean crlf, boolean bom) {
        return new FileEditorWindow(null, "test-server", "/home/u/app.conf",
                new TextFileCodec.Decoded(text, crlf, bom, false), saver);
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private void type(String s) throws Exception {
        onEdt(() -> window.textArea().append(s));
    }

    private void save() throws Exception {
        onEdt(() -> window.getRootPane().getActionMap().get("save-file")
                .actionPerformed(new java.awt.event.ActionEvent(window, 0, "save")));
    }

    private void close(int prompt) throws Exception {
        onEdt(() -> {
            window.setClosePrompt(() -> prompt);
            window.requestClose();
        });
    }

    @Test
    void 처음에는_고친_내용이_없고_글자를_고치면_고친_것으로_표시된다() throws Exception {
        assertFalse(window.isDirty());

        type("c\n");

        assertTrue(window.isDirty());
        assertEquals("a\nb\nc\n", window.textArea().getText());
    }

    @Test
    void 고친_내용이_없으면_저장_요청도_하지_않는다() throws Exception {
        save();

        assertEquals(0, saver.saved.size());
    }

    @Test
    void 저장하면_원래_줄바꿈과_BOM으로_되돌려_보낸다() throws Exception {
        onEdt(() -> {
            window.closeWithoutAsking();
            window = create("a\nb\n", true, true);
        });
        type("c\n");

        save();

        assertEquals(1, saver.saved.size());
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '\r', '\n', 'b', '\r', '\n', 'c', '\r', '\n'},
                saver.saved.getFirst());
    }

    @Test
    void 저장이_성공하면_고친_표시가_사라진다() throws Exception {
        type("c\n");
        save();

        onEdt(() -> saver.finish(FileEditorWindow.SaveResult.saved()));

        assertFalse(window.isDirty());
        assertTrue(window.statusText().startsWith("저장했어요"));
    }

    @Test
    void 저장하는_사이_또_고쳤으면_아직_저장_전으로_남는다() throws Exception {
        type("c\n");
        save();
        type("d\n");   // 저장 결과가 오기 전에 더 고쳤다

        onEdt(() -> saver.finish(FileEditorWindow.SaveResult.saved()));

        assertTrue(window.isDirty());
    }

    @Test
    void 저장이_실패하면_고친_표시가_남고_이유가_보인다() throws Exception {
        type("c\n");
        save();

        onEdt(() -> saver.finish(FileEditorWindow.SaveResult.failed("서버와 연결이 끊겼어요")));

        assertTrue(window.isDirty());
        assertEquals("저장하지 못했어요: 서버와 연결이 끊겼어요", window.statusText());
    }

    @Test
    void 덮어쓰기를_거절하면_저장하지_않은_것으로_남는다() throws Exception {
        type("c\n");
        save();

        onEdt(() -> saver.finish(FileEditorWindow.SaveResult.declined()));

        assertTrue(window.isDirty());
        assertEquals("저장하지 않았어요", window.statusText());
    }

    @Test
    void 저장_중에_같은_저장을_또_누르면_한_번만_보낸다() throws Exception {
        type("c\n");

        save();
        save();

        assertEquals(1, saver.saved.size());
    }

    @Test
    void 읽기_모드가_되면_저장하지_않는다() throws Exception {
        type("c\n");
        onEdt(() -> window.setReadOnly(true));

        save();

        assertEquals(0, saver.saved.size());
        assertFalse(window.textArea().isEditable());
    }

    @Test
    void 고친_내용이_없으면_묻지_않고_닫는다() throws Exception {
        close(CloseChoice.CANCEL.ordinal());   // 물었다면 취소라서 안 닫혔을 것이다

        assertTrue(window.isDisposed());
    }

    @Test
    void 고친_내용이_있을_때_확인_창을_X로_닫으면_저장하지_않고_닫힌다() throws Exception {
        type("c\n");

        close(javax.swing.JOptionPane.CLOSED_OPTION);

        assertTrue(window.isDisposed());
        assertEquals(0, saver.saved.size(), "저장하지 않았다");
    }

    @Test
    void 저장_안_함을_고르면_저장하지_않고_닫힌다() throws Exception {
        type("c\n");

        close(1);

        assertTrue(window.isDisposed());
        assertEquals(0, saver.saved.size());
    }

    @Test
    void 취소를_고르면_열려_있다() throws Exception {
        type("c\n");

        close(2);

        assertFalse(window.isDisposed());
        assertTrue(window.isDirty());
    }

    @Test
    void 저장을_고르면_저장이_끝난_뒤에_닫힌다() throws Exception {
        type("c\n");

        close(0);
        assertFalse(window.isDisposed(), "저장이 끝나기 전에는 닫지 않는다");
        assertEquals(1, saver.saved.size());
        assertArrayEquals("a\nb\nc\n".getBytes(StandardCharsets.UTF_8), saver.saved.getFirst());

        onEdt(() -> saver.finish(FileEditorWindow.SaveResult.saved()));

        assertTrue(window.isDisposed());
    }

    @Test
    void 저장을_골랐는데_실패하면_닫지_않는다() throws Exception {
        type("c\n");
        close(0);

        onEdt(() -> saver.finish(FileEditorWindow.SaveResult.failed("권한이 없어요")));

        assertFalse(window.isDisposed());
        assertTrue(window.isDirty());
    }

    @Test
    void 닫히면_닫힘_알림이_한_번_온다() throws Exception {
        int[] closed = {0};
        onEdt(() -> window.onClosed(() -> closed[0]++));

        onEdt(window::closeWithoutAsking);
        onEdt(window::closeWithoutAsking);

        assertEquals(1, closed[0]);
    }

    @Test
    void 형식_안내는_인코딩과_줄바꿈을_보여_준다() {
        assertEquals("UTF-8 · LF", FileEditorWindow.formatText(new TextFileCodec.Decoded("", false, false, false)));
        assertEquals("UTF-8 (BOM) · CRLF", FileEditorWindow.formatText(new TextFileCodec.Decoded("", true, true, false)));
        assertEquals("UTF-8 · LF · 줄바꿈이 섞여 있어요", FileEditorWindow.formatText(new TextFileCodec.Decoded("", false, false, true)));
    }
}
