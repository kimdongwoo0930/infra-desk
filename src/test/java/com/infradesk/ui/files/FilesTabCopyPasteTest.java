package com.infradesk.ui.files;

import com.infradesk.app.InfraDeskApp;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.RemoteFiles;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파일 탭의 복사/붙여넣기를 데모 서버(메모리 파일 트리)로 실제로 돌려 본다. 키 입력은 표의 단축키 동작을 직접 불러서 흉내 낸다.
 * 데모 트리는 서버 이름별로 하나라서, 시험마다 이름이 겹치지 않게 한다.
 */
class FilesTabCopyPasteTest {

    private static final TerminalService SERVICE = InfraDeskApp.demoTerminalService();
    private static final String KEY = FilePane.IS_MAC ? "meta" : "ctrl";

    private final List<FilesTab> tabs = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (FilesTab tab : tabs) {
            onEdt(() -> {
                tab.close();
                return null;
            });
        }
        FileClipboard.clear();
    }

    private static Server server(String name) {
        return new Server("id-" + name, "acc-1", name, ServerStatus.RUNNING, "test-region-1", "VM.Test", 2, 12, null, null, null);
    }

    private FilesTab open(Server server) throws Exception {
        FilesTab tab = onEdt(() -> new FilesTab(SERVICE, server));
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

    /** 그 이름의 행을 고른다. */
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

    /** 표에서 단축키를 누른 것처럼 동작을 부른다. */
    private static void press(FilePane pane, String letter) throws Exception {
        JTable table = table(pane);
        onEdt(() -> {
            table.getActionMap().get("pane-" + KEY + " " + letter).actionPerformed(new ActionEvent(table, 0, letter));
            return null;
        });
    }

    /** 서버에 내용이 있는 파일을 하나 만들어 두고, 그 서버 탭의 목록에 보이게 한다. */
    private String seed(FilesTab tab, Server server, String fileName, String content) throws Exception {
        RemoteFiles rf = SERVICE.openFiles(server, null);
        String home = rf.home();
        Path local = Files.createTempFile("copytest", ".txt");
        Files.writeString(local, content);
        rf.upload(local, home + "/" + fileName, n -> { });
        Files.delete(local);
        FilePane remote = pane(tab, "remotePane");
        onEdt(() -> {
            remote.reload();
            return null;
        });
        await(fileName + " 목록에 나타남", () -> onEdt(() -> remote.names().contains(fileName)));
        return home;
    }

    private static String read(Server server, String path) throws Exception {
        RemoteFiles rf = SERVICE.openFiles(server, null);
        Path local = Files.createTempFile("copyread", ".txt");
        try {
            rf.download(path, local, n -> { });
            return Files.readString(local, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private static boolean exists(Server server, String path) throws Exception {
        return SERVICE.openFiles(server, null).exists(path);
    }

    @Test
    void 같은_폴더에_붙여넣으면_복사_이름이_붙는다() throws Exception {
        Server a = server("copytest-same");
        FilesTab tab = open(a);
        String home = seed(tab, a, "memo.txt", "안녕 서버");
        FilePane remote = pane(tab, "remotePane");
        select(remote, "memo.txt");

        press(remote, "C");
        press(remote, "V");

        await("복사본 생성", () -> exists(a, home + "/memo 복사.txt"));
        assertEquals("안녕 서버", read(a, home + "/memo 복사.txt"));
        assertEquals("안녕 서버", read(a, home + "/memo.txt"), "원본은 그대로");
        // 한 번 더 붙여넣으면 번호가 붙는다.
        press(remote, "V");
        await("두 번째 복사본 생성", () -> exists(a, home + "/memo 복사 2.txt"));
    }

    @Test
    void 다른_서버의_파일_탭에서도_붙여넣을_수_있다() throws Exception {
        Server a = server("copytest-from");
        Server b = server("copytest-to");
        FilesTab tabA = open(a);
        FilesTab tabB = open(b);
        seed(tabA, a, "report.txt", "서버 A의 내용");
        select(pane(tabA, "remotePane"), "report.txt");
        press(pane(tabA, "remotePane"), "C");

        press(pane(tabB, "remotePane"), "V");

        String homeB = SERVICE.openFiles(b, null).home();
        await("다른 서버에 복사됨", () -> exists(b, homeB + "/report.txt"));
        assertEquals("서버 A의 내용", read(b, homeB + "/report.txt"));
        assertTrue(exists(a, SERVICE.openFiles(a, null).home() + "/report.txt"), "원본은 그대로");
    }

    @Test
    void 내_컴퓨터에서_복사해_서버에_붙여넣는다() throws Exception {
        Server a = server("copytest-up");
        FilesTab tab = open(a);
        FilePane local = pane(tab, "localPane");
        FilePane remote = pane(tab, "remotePane");
        await("내 컴퓨터 목록", () -> onEdt(() -> local.ready() && !local.names().isEmpty()));
        String first = onEdt(() -> {
            try {
                JTable t = table(local);
                for (int row = 0; row < t.getRowCount(); row++) {
                    RemoteFile f = (RemoteFile) t.getValueAt(row, 0);
                    if (!f.directory() && !remote.names().contains(f.name())) {
                        return f.name();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
        assertNotNull(first, "데모 내 컴퓨터 폴더에 서버에 없는 파일이 있어야 한다");
        select(local, first);

        press(local, "C");
        press(remote, "V");

        await("서버에 올라감", () -> onEdt(() -> remote.names().contains(first)));
    }

    @Test
    void 내_컴퓨터_안에서_같은_폴더에_붙여넣으면_복사_이름이_붙는다() throws Exception {
        Server a = server("copytest-local");
        FilesTab tab = open(a);
        FilePane local = pane(tab, "localPane");
        await("내 컴퓨터 목록", () -> onEdt(() -> local.ready() && local.names().contains("notes.txt")));
        select(local, "notes.txt");

        press(local, "C");
        press(local, "V");

        await("복사본이 목록에 나타남", () -> onEdt(() -> local.names().contains("notes 복사.txt")));
    }
}
