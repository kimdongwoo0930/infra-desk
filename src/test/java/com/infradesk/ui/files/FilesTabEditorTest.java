package com.infradesk.ui.files;

import com.infradesk.app.InfraDeskApp;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.RemoteFiles;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 파일 탭의 앱 안 편집 창을 데모 서버(메모리 파일 트리)로 처음부터 끝까지 돌려 본다: 파일을 열고, 고치고, 저장해서
 * 서버 내용이 바뀌는지, 전송 목록에 줄이 안 생기는지, 닫을 때 확인이 어떻게 처리되는지. 창은 만들기만 하고 화면에 띄우지
 * 않는다(진짜 창을 띄웠다 바로 닫으면 macOS의 화면 갱신 스레드가 드물게 죽어서 시험 전체가 멈춘 일이 있었다).
 * 창을 만들 수 없는 환경(헤드리스)에서는 건너뛴다.
 */
class FilesTabEditorTest {

    private static final TerminalService SERVICE = InfraDeskApp.demoTerminalService();

    private final List<FilesTab> tabs = new ArrayList<>();

    @BeforeEach
    void needsDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "창을 띄울 수 없는 환경");
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (FilesTab tab : tabs) {
            onEdt(() -> {
                tab.close();
                return null;
            });
        }
    }

    private static Server server(String name) {
        return new Server("id-" + name, "acc-1", name, ServerStatus.RUNNING, "test-region-1", "VM.Test", 2, 12, null, null, null);
    }

    private FilesTab open(Server server) throws Exception {
        FilesTab tab = onEdt(() -> {
            FilesTab created = new FilesTab(SERVICE, server);
            created.setEditorShowerForTest(w -> { });   // 창을 만들기만 하고 띄우지 않는다
            return created;
        });
        tabs.add(tab);
        FilePane remote = pane(tab, "remotePane");
        await("서버 파일 목록", () -> onEdt(() -> remote.ready() && remote.cwd() != null));
        return tab;
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = task.call();
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) {
            throw new RuntimeException(error[0]);
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("시간 안에 안 됐어요: " + what);
    }

    private static FilePane pane(FilesTab tab, String field) throws Exception {
        Field f = FilesTab.class.getDeclaredField(field);
        f.setAccessible(true);
        return (FilePane) f.get(tab);
    }

    private static JTable table(FilePane pane) throws Exception {
        Field f = FilePane.class.getDeclaredField("table");
        f.setAccessible(true);
        return (JTable) f.get(pane);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FileEditorWindow> editors(FilesTab tab) throws Exception {
        Field f = FilesTab.class.getDeclaredField("editors");
        f.setAccessible(true);
        return (Map<String, FileEditorWindow>) f.get(tab);
    }

    private static void select(FilePane pane, String name) throws Exception {
        JTable table = table(pane);
        onEdt(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                if (((RemoteFile) table.getValueAt(row, 0)).name().equals(name)) {
                    table.setRowSelectionInterval(row, row);
                    return null;
                }
            }
            throw new AssertionError("목록에 없어요: " + name);
        });
    }

    /** 더블클릭과 같은 길(Enter 동작)로 그 파일을 연다. */
    private static void activate(FilesTab tab, String name) throws Exception {
        FilePane remote = pane(tab, "remotePane");
        select(remote, name);
        JTable table = table(remote);
        onEdt(() -> {
            table.getActionMap().get("pane-ENTER").actionPerformed(new ActionEvent(table, 0, "enter"));
            return null;
        });
    }

    private static String seed(FilesTab tab, Server server, String fileName, byte[] content) throws Exception {
        RemoteFiles rf = SERVICE.openFiles(server, null);
        String home = rf.home();
        Path local = Files.createTempFile("editortest", ".txt");
        Files.write(local, content);
        rf.upload(local, home + "/" + fileName, n -> { });
        Files.delete(local);
        FilePane remote = pane(tab, "remotePane");
        onEdt(() -> {
            remote.reload();
            return null;
        });
        await(fileName + " 목록에 나타남", () -> onEdt(() -> remote.names().contains(fileName)));
        return home + "/" + fileName;
    }

    private static String read(Server server, String path) throws Exception {
        RemoteFiles rf = SERVICE.openFiles(server, null);
        Path local = Files.createTempFile("editorread", ".txt");
        try {
            rf.download(path, local, n -> { });
            return Files.readString(local, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private static FileEditorWindow waitForEditor(FilesTab tab, String path) throws Exception {
        await("편집 창이 열림", () -> onEdt(() -> editors(tab).containsKey(path)));
        return onEdt(() -> editors(tab).get(path));
    }

    private static void save(FileEditorWindow window) throws Exception {
        onEdt(() -> {
            window.getRootPane().getActionMap().get("save-file").actionPerformed(new ActionEvent(window, 0, "save"));
            return null;
        });
    }

    @Test
    void 더블클릭으로_열고_고쳐서_저장하면_서버_내용이_바뀌고_전송_목록에는_줄이_없다() throws Exception {
        Server a = server("editortest-save");
        FilesTab tab = open(a);
        String path = seed(tab, a, "app.conf", "listen 80;\n".getBytes(StandardCharsets.UTF_8));

        activate(tab, "app.conf");
        FileEditorWindow window = waitForEditor(tab, path);
        assertEquals("listen 80;\n", window.textArea().getText(), "받아 저장하지 않고 내용을 읽어서 보여 준다");
        activate(tab, "app.conf");   // 같은 파일을 또 열어도 창은 하나
        assertSame(window, onEdt(() -> editors(tab).get(path)));
        assertEquals(1, onEdt(() -> editors(tab).size()));

        onEdt(() -> {
            window.textArea().append("listen 8080;\n");
            return null;
        });
        assertEquals(1, tab.unsavedEdits(), "저장하지 않은 편집 창은 종료·서버 삭제 때 확인에 센다");
        save(window);

        await("서버 내용이 바뀜", () -> read(a, path).equals("listen 80;\nlisten 8080;\n"));
        await("저장 완료 표시", () -> onEdt(() -> !window.isDirty()));
        Field t = FilesTab.class.getDeclaredField("transfers");
        t.setAccessible(true);
        TransferPanel panel = (TransferPanel) t.get(tab);
        assertTrue(onEdt(panel::isEmpty), "저장 때문에 전송 목록에 줄이 생기면 안 된다");
        assertEquals(0, tab.unsavedEdits());

        // 두 번째 저장: 첫 저장 뒤 서버 파일 상태를 다시 기록했다면 "서버에서 바뀌었어요" 확인 창 없이 올라간다.
        onEdt(() -> {
            window.textArea().append("# end\n");
            return null;
        });
        save(window);
        await("두 번째 저장", () -> read(a, path).endsWith("# end\n"));
    }

    @Test
    void 고친_내용이_있는_창의_확인을_X로_닫으면_저장하지_않고_닫힌다() throws Exception {
        Server a = server("editortest-close");
        FilesTab tab = open(a);
        String path = seed(tab, a, "notes.txt", "원본\n".getBytes(StandardCharsets.UTF_8));
        activate(tab, "notes.txt");
        FileEditorWindow window = waitForEditor(tab, path);
        onEdt(() -> {
            window.textArea().append("저장하지 않을 내용\n");
            window.setClosePrompt(() -> javax.swing.JOptionPane.CLOSED_OPTION);   // 확인 창을 X로 닫았다
            window.requestClose();
            return null;
        });

        assertTrue(onEdt(window::isDisposed));
        assertFalse(onEdt(() -> editors(tab).containsKey(path)), "닫힌 창은 목록에서 빠진다");
        assertEquals("원본\n", read(a, path), "저장하지 않았다");
        assertEquals(0, tab.unsavedEdits());
    }

    @Test
    void 읽기_모드가_되면_열려_있던_편집_창도_저장하지_못한다() throws Exception {
        Server a = server("editortest-readonly");
        FilesTab tab = open(a);
        String path = seed(tab, a, "secret.txt", "원본\n".getBytes(StandardCharsets.UTF_8));
        activate(tab, "secret.txt");
        FileEditorWindow window = waitForEditor(tab, path);
        onEdt(() -> {
            window.textArea().append("고침\n");
            tab.setReadOnly(true);
            return null;
        });

        save(window);

        assertFalse(onEdt(() -> window.textArea().isEditable()));
        assertEquals("읽기 모드라 저장할 수 없어요", window.statusText());
        assertEquals("원본\n", read(a, path), "서버는 바뀌지 않았다");
    }

    @Test
    void 탭을_닫으면_열린_편집_창도_묻지_않고_닫힌다() throws Exception {
        Server a = server("editortest-tabclose");
        FilesTab tab = open(a);
        String path = seed(tab, a, "x.txt", "x\n".getBytes(StandardCharsets.UTF_8));
        activate(tab, "x.txt");
        FileEditorWindow window = waitForEditor(tab, path);
        onEdt(() -> {
            window.textArea().append("y\n");
            tab.close();
            return null;
        });

        assertTrue(onEdt(window::isDisposed));
    }

    @Test
    void 서버_경로의_폴더를_구한다() {
        assertEquals("/home/u", FilesTab.parentOf("/home/u/app.conf"));
        assertEquals("/", FilesTab.parentOf("/app.conf"));
        assertEquals("/", FilesTab.parentOf("/"));
    }

    @Test
    void 열_수_없는_이유를_파일_이름과_함께_알려_준다() {
        assertTrue(FilesTab.editorRefusal(TextFileCodec.Problem.BINARY, "a.png").contains("a.png"));
        assertTrue(FilesTab.editorRefusal(TextFileCodec.Problem.NOT_UTF8, "old.txt").contains("EUC-KR"));
        assertNotNull(FilesTab.editorRefusal(TextFileCodec.Problem.LONE_CR, "mac.txt"));
    }
}
