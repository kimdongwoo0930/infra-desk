package com.infradesk.ui;

import com.infradesk.app.InfraDeskApp;
import com.infradesk.demo.DemoProviderFactory;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.InventoryService;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/**
 * 개발 도구: 데모 데이터로 화면을 오프스크린 렌더링해서 build/snapshots/*.png로 저장한다.
 * 화면 녹화 권한 없이도 UI 변경을 확인할 수 있다. {@code ./gradlew snapshot}으로 실행한다.
 */
public final class UiSnapshot {

    private static final File DIR = new File("build/snapshots");

    private UiSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        DIR.mkdirs();
        InventoryService service = InfraDeskApp.demoService(new DemoProviderFactory(Clock.systemUTC(), Duration.ZERO));
        List<AccountInventory> inventory = service.loadAll();
        // "정지 중" 화면을 위해 discord-bot이 방금 정지된 별도의 데모 세계.
        InventoryService stoppingService = InfraDeskApp.demoService(new DemoProviderFactory(Clock.systemUTC(), Duration.ZERO));
        var stoppingInventory = stoppingService.loadAll();
        stoppingService.control(stoppingService.accounts().getFirst(),
                stoppingInventory.getFirst().servers().getFirst().id(), com.infradesk.service.ServerAction.STOP);
        List<AccountInventory> stopping = stoppingService.loadAll();

        MainFrame[] mainFrame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            mainFrame[0] = new MainFrame(service, fastLiveTerminalService(), InfraDeskApp.demoAlertService(), true);
            mainFrame[0].setInventory(inventory);
        });
        // 디자인 v2 공통 부품 모음 (v2-components.png)
        SwingUtilities.invokeAndWait(() -> {
            try {
                ComponentGallery.render(DIR);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Thread.sleep(800);
        // 컨테이너 탭 (디자인 v2 3단계): 탭을 열면 첫 컨테이너의 로그를 SSH(데모)로 읽어 오므로 잠깐 기다린 뒤 찍는다.
        // 찍은 뒤에는 개요 탭으로 되돌려서 아래의 다른 스냅샷이 달라지지 않게 한다.
        com.infradesk.ui.containers.ContainerLogWindow[] logWindow = new com.infradesk.ui.containers.ContainerLogWindow[1];
        SwingUtilities.invokeAndWait(() -> mainFrame[0].serverScreen().showContainersTab());
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(mainFrame[0], mainFrame[0].getContentPane(), 1280, 800, "server-containers.png");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            // 로그 별도 창 (메인 화면의 로그 패널과 같은 모양이어야 한다). 열면 지금 보던 컨테이너 로그를 읽어 온다.
            var logs = mainFrame[0].serverScreen().logs();
            var window = mainFrame[0].openLogWindow(inventory.getFirst().servers().getFirst(), logs.selected());
            logWindow[0] = window;
            mainFrame[0].serverScreen().showOverviewTab();
        });
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(logWindow[0], logWindow[0].getContentPane(), 1100, 680, "container-log-window.png");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            logWindow[0].dispose();
        });

        // 사이드바: 이름만 보이는 폭(150), 닫힘, 다시 펼침. 폭이 바뀌면 이름만 모드로 바뀌는 것은 크기 변경 이벤트로
        // 일어나므로(비동기) 바꾼 뒤 잠깐 기다렸다가 찍는다. 마지막에 원래대로 돌려 아래 스냅샷이 달라지지 않게 한다.
        SwingUtilities.invokeAndWait(() -> mainFrame[0].setSidebarWidth(150));
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(mainFrame[0], mainFrame[0].getContentPane(), 1280, 800, "sidebar-compact.png");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            mainFrame[0].collapseSidebar();
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(mainFrame[0], mainFrame[0].getContentPane(), 1280, 800, "sidebar-collapsed.png");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            mainFrame[0].expandSidebar();
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            try {
                MainFrame empty = new MainFrame(service, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
                empty.setInventory(List.of());
                write(empty, empty.getContentPane(), 1280, 800, "main-empty.png");

                MainFrame frame = mainFrame[0];
                write(frame, frame.getContentPane(), 1280, 800, "main.png");
                write(frame, frame.getContentPane(), 1280, 1080, "main-full.png");
                // 창이 좁으면 개요의 오른쪽 열이 왼쪽 아래로 내려가서 한 열로 쌓인다.
                write(frame, frame.getContentPane(), 980, 1700, "main-narrow.png");
                frame.getContentPane().setSize(1280, 800);

                // 호버: 각 차트 위(왼쪽, 가운데, 오른쪽 끝)로 마우스를 옮기며 렌더링한다.
                List<java.awt.Component> charts = new java.util.ArrayList<>();
                collect(frame.getContentPane(), "HoverChartPanel", charts);
                double[] at = {0.3, 0.55, 0.97};
                for (int i = 0; i < charts.size() && i < at.length; i++) {
                    java.awt.Component c = charts.get(i);
                    c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_MOVED,
                            System.currentTimeMillis(), 0, (int) (c.getWidth() * at[i]), 20, 0, false));
                }
                write(frame, frame.getContentPane(), 1280, 800, "main-hover.png");
                for (java.awt.Component c : charts) {
                    c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_EXITED,
                            System.currentTimeMillis(), 0, -1, -1, 0, false));
                }

                MainFrame stoppingFrame = new MainFrame(stoppingService, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
                stoppingFrame.setInventory(stopping);
                write(stoppingFrame, stoppingFrame.getContentPane(), 1280, 800, "main-stopping.png");
                stoppingFrame.dispose();

                JDialog dialog = new AddAccountDialog(frame, service, false);
                write(dialog, dialog.getContentPane(), dialog.getWidth(), dialog.getContentPane().getPreferredSize().height,
                        "add-account.png");
                dialog.dispose();

                // 직접 연결한 기기(데모 Mac mini): 그 다이얼로그와 대시보드.
                JDialog addSsh = new AddSshServerDialog(frame, service, null);
                write(addSsh, addSsh.getContentPane(), addSsh.getWidth(), addSsh.getContentPane().getPreferredSize().height,
                        "add-ssh-server.png");
                addSsh.dispose();

                JDialog editAccount = new AddAccountDialog(frame, service, true, service.accounts().getFirst());
                write(editAccount, editAccount.getContentPane(), editAccount.getWidth(),
                        editAccount.getContentPane().getPreferredSize().height, "edit-account.png");
                editAccount.dispose();

                var updates = new com.infradesk.service.UpdateService(java.net.http.HttpClient.newHttpClient(),
                        java.net.URI.create("http://127.0.0.1:1"), "x", null);
                JDialog settings = new SettingsDialog(frame, InfraDeskApp.demoAlertService(), false, updates,
                        com.infradesk.app.LaunchAtLogin.forCurrentOs(), () -> { });
                write(settings, settings.getContentPane(), settings.getWidth(),
                        settings.getContentPane().getPreferredSize().height, "settings.png");
                settings.dispose();

                // Windows 트레이 메뉴(Swing 팝업): 메인 목록과 첫 서버의 하위 메뉴를 나란히.
                TrayController.Actions noop = (TrayController.Actions) java.lang.reflect.Proxy.newProxyInstance(
                        TrayController.Actions.class.getClassLoader(), new Class<?>[]{TrayController.Actions.class}, (o, m, a) -> null);
                var entries = TrayController.entries(inventory, java.util.Map.of(inventory.getFirst().servers().getFirst().id(), 23.0),
                        " · 18:42 갱신", true, null, noop);
                var sub = entries.stream().filter(e -> e instanceof TrayController.Entry.ServerMenu).findFirst()
                        .map(e -> ((TrayController.Entry.ServerMenu) e).items()).orElseThrow();
                // 팝업은 표시되기 전에는 보이지 않으므로 직접 배치하고 그린다.
                javax.swing.JPopupMenu[] menus = {SwingTrayMenu.build(entries), SwingTrayMenu.build(sub)};
                javax.swing.JWindow trayWindow = new javax.swing.JWindow();
                trayWindow.addNotify();
                int trayW = 16;
                int trayH = 0;
                for (var m : menus) {
                    trayWindow.getContentPane().add(m);
                    m.setSize(m.getPreferredSize());
                    layoutAll(m);
                    trayW += m.getWidth() + 16;
                    trayH = Math.max(trayH, m.getHeight() + 32);
                }
                BufferedImage trayImg = new BufferedImage(trayW * 2, trayH * 2, BufferedImage.TYPE_INT_RGB);
                Graphics2D tg = trayImg.createGraphics();
                tg.setColor(Theme.APP_BG);
                tg.fillRect(0, 0, trayImg.getWidth(), trayImg.getHeight());
                tg.scale(2, 2);
                tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int x = 16;
                for (var m : menus) {
                    Graphics2D mg = (Graphics2D) tg.create(x, 16, m.getWidth(), m.getHeight());
                    m.paint(mg);
                    mg.dispose();
                    x += m.getWidth() + 16;
                }
                tg.dispose();
                ImageIO.write(trayImg, "png", new File(DIR, "tray-menu-windows.png"));
                trayWindow.dispose();

                var installer = new com.infradesk.service.UpdateInstaller(java.net.http.HttpClient.newHttpClient(),
                        com.infradesk.service.UpdateInstaller.Platform.MAC, java.nio.file.Path.of("/Applications/InfraDesk.app"),
                        java.nio.file.Path.of("build/tmp"), java.nio.file.Path.of("build/tmp/update.log"), false);
                var release = new com.infradesk.service.UpdateService.Release(8, "abc1234", "https://p", "https://d",
                        "https://u/InfraDesk-beta-macOS.zip", "https://u/SHA256SUMS.txt");
                UpdateProgressDialog progress = new UpdateProgressDialog(frame, installer, release, p -> { });
                progress.previewProgress(31_400_000, 74_900_000);
                write(progress, progress.getContentPane(), progress.getWidth(), progress.getContentPane().getPreferredSize().height,
                        "update-progress.png");
                progress.dispose();

                JDialog ssh = new com.infradesk.ui.terminal.SshSettingsDialog(frame, InfraDeskApp.demoTerminalService(),
                        inventory.getFirst().servers().getFirst());
                write(ssh, ssh.getContentPane(), ssh.getWidth(), ssh.getContentPane().getPreferredSize().height,
                        "ssh-settings.png");
                ssh.dispose();
                empty.dispose();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // 직접 연결한 Mac mini: 상세 정보와 첫 SSH 샘플이 비동기로 도착한다.
        MainFrame[] sshFrame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            var mac = inventory.stream().filter(i -> i.account().provider() == com.infradesk.core.ProviderType.SSH)
                    .findFirst().orElseThrow().servers().getFirst();
            sshFrame[0] = new MainFrame(service, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
            sshFrame[0].setInventory(inventory);
            sshFrame[0].showServer(mac);
        });
        Thread.sleep(2500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(sshFrame[0], sshFrame[0].getContentPane(), 1280, 800, "main-ssh.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            sshFrame[0].dispose();
        });
        // 열린 포트가 많으면 칩이 다음 줄로 넘어가고 정보 카드가 그만큼 길어진다(잘리지 않는다). 값을 넣은 뒤 화면이
        // 한 번 다시 계산할 시간을 주고 찍는다(실제 앱과 같은 순서).
        SwingUtilities.invokeAndWait(() -> {
            mainFrame[0].addNotify();
            mainFrame[0].getContentPane().setSize(1280, 1000);
            layoutAll(mainFrame[0].getContentPane());
            mainFrame[0].serverScreen().setFacts(new com.infradesk.ssh.HostFacts(java.time.Duration.ofDays(6), "macOS 26.6.2",
                    42L << 30, 99L << 30, List.of(22, 80, 443, 5000, 7000, 49154, 59869, 61000)), java.time.Instant.now());
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(mainFrame[0], mainFrame[0].getContentPane(), 1280, 1000, "main-ports.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            mainFrame[0].getContentPane().setSize(1280, 800);
        });
        // 모니터링 탭 (디자인 v2 4단계): 기본 1시간, 24시간, 실시간 순서로 찍는다. 기간 칸을 누르면 그 기간을 비동기로
        // 읽어 오므로 잠깐 기다린다. 실시간은 데모 /proc 스트림이 샘플을 쌓을 시간을 준다. 마지막에 1시간으로 돌려
        // 실시간을 끄고 개요 탭으로 돌아가서 아래 스냅샷에 영향이 없게 한다.
        SwingUtilities.invokeAndWait(() -> {
            MainFrame live = mainFrame[0];
            live.addNotify();
            live.serverScreen().showMonitoringTab();
        });
        Thread.sleep(1200);
        snapshotMonitoring("monitoring.png", mainFrame[0]);
        // 첫 번째(CPU) 차트 위에 마우스를 올린다: 세 차트가 같은 시점에 선과 값 상자를 그려야 한다.
        SwingUtilities.invokeAndWait(() -> {
            var chart = find(mainFrame[0].getContentPane(), "ZoomChartPanel");
            var move = new java.awt.event.MouseEvent(chart, java.awt.event.MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, chart.getWidth() * 3 / 5, 80, 0, false);
            for (var l : chart.getMouseMotionListeners()) {
                l.mouseMoved(move);
            }
        });
        snapshotMonitoring("monitoring-hover.png", mainFrame[0]);
        SwingUtilities.invokeAndWait(() -> {
            var chart = find(mainFrame[0].getContentPane(), "ZoomChartPanel");
            var exit = new java.awt.event.MouseEvent(chart, java.awt.event.MouseEvent.MOUSE_EXITED,
                    System.currentTimeMillis(), 0, -1, -1, 0, false);
            for (var l : chart.getMouseListeners()) {
                l.mouseExited(exit);
            }
        });
        SwingUtilities.invokeAndWait(() -> ((javax.swing.AbstractButton) findText(mainFrame[0].getContentPane(), "24시간")).doClick());
        Thread.sleep(1200);
        snapshotMonitoring("monitoring-24h.png", mainFrame[0]);
        SwingUtilities.invokeAndWait(() -> ((javax.swing.AbstractButton) findText(mainFrame[0].getContentPane(), "실시간")).doClick());
        Thread.sleep(9000);
        snapshotMonitoring("monitoring-live.png", mainFrame[0]);
        SwingUtilities.invokeAndWait(() -> {
            ((javax.swing.AbstractButton) findText(mainFrame[0].getContentPane(), "1시간")).doClick();   // 실시간을 끈다
            mainFrame[0].serverScreen().showOverviewTab();
        });
        Thread.sleep(600);

        // 파일 탭 (디자인 v2 5단계): 서버 화면 안의 파일 탭. 서버 파일 하나를 받아서 전송 목록을 채우고, 다른 하나를 편집
        // 세션으로 열어 배너를 띄운 뒤, 파일 둘을 골라 선택 작업 막대까지 보이게 찍는다. 편집기는 실제로 띄우지 않는다.
        try {
            Class<?> editor = Class.forName("com.infradesk.ui.files.ExternalEditor");
            java.lang.reflect.Field hook = editor.getDeclaredField("launcherOverride");
            hook.setAccessible(true);
            hook.set(null, (java.util.function.Consumer<java.nio.file.Path>) opened -> { });
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        SwingUtilities.invokeAndWait(() -> mainFrame[0].serverScreen().showFilesTab());
        Thread.sleep(1500);
        java.util.function.BiConsumer<String[], String> pickRemote = (names, button) -> {
            List<java.awt.Component> tabs = new java.util.ArrayList<>();
            collect(mainFrame[0].getContentPane(), "FilesTab", tabs);
            List<java.awt.Component> tables = new java.util.ArrayList<>();
            collect((Container) tabs.getFirst(), "JTable", tables);
            javax.swing.JTable remote = (javax.swing.JTable) tables.get(1);
            remote.clearSelection();
            for (int row = 0; row < remote.getRowCount(); row++) {
                for (String name : names) {
                    if (remote.getValueAt(row, 0).toString().contains(name)) {
                        remote.addRowSelectionInterval(row, row);
                    }
                }
            }
            if (button != null) {
                List<java.awt.Component> buttons = new java.util.ArrayList<>();
                collect((Container) tabs.getFirst(), "JButton", buttons);
                // 같은 글자("편집")의 버튼이 내 컴퓨터 쪽에도 있다: 서버 쪽 것은 뒤에 온다.
                buttons.stream().map(b -> (javax.swing.AbstractButton) b)
                        .filter(b -> button.equals(b.getText())).reduce((first, second) -> second).orElseThrow().doClick();
            }
        };
        SwingUtilities.invokeAndWait(() -> pickRemote.accept(new String[] {"docker-compose.yml"}, "내 컴퓨터로 받기"));
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> pickRemote.accept(new String[] {"README.md"}, "편집"));
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> pickRemote.accept(new String[] {"README.md", "docker-compose.yml"}, null));
        snapshotMonitoring("files-tab.png", mainFrame[0]);
        // 정리: 편집을 끝내고 끝난 전송을 지워서 아래 스냅샷에 영향이 없게 한다.
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(mainFrame[0].getContentPane(), "JButton", buttons);
            for (String text : new String[] {"편집 끝내기", "끝난 항목 지우기"}) {
                buttons.stream().map(b -> (javax.swing.AbstractButton) b).filter(b -> text.equals(b.getText()))
                        .findFirst().orElseThrow().doClick();
            }
            mainFrame[0].serverScreen().showOverviewTab();
        });
        Thread.sleep(300);
        // 데모의 내 컴퓨터 폴더는 아래 SFTP 시나리오와 공유된다. 위에서 받은 파일을 지워 같은 이름 충돌이 생기지 않게 한다.
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(InfraDeskApp.demoTerminalService().localFiles().home())
                .resolve("docker-compose.yml"));

        // 터미널: 데모 세션을 열고, 가짜 셸이 배너를 출력할 시간을 준 뒤 렌더링한다.
        MainFrame[] terminalFrame = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            terminalFrame[0] = new MainFrame(service, InfraDeskApp.demoTerminalService(), InfraDeskApp.demoAlertService(), true);
            terminalFrame[0].setInventory(inventory);
            terminalFrame[0].addNotify();
            terminalFrame[0].getContentPane().setSize(1280, 800);
            layoutAll(terminalFrame[0].getContentPane());
            terminalFrame[0].openTerminalFor(inventory.get(1).servers().getFirst());
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            var widget = find(terminalFrame[0].getContentPane(), com.jediterm.terminal.ui.JediTermWidget.class);
            try {
                widget.getTtyConnector().write("free -h\rdocker ps\r");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(1000);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(terminalFrame[0], terminalFrame[0].getContentPane(), 1280, 800, "terminal.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // 데모 컨테이너 안의 MySQL 콘솔. 컨테이너 패널의 "MySQL 콘솔" 버튼을 누른 것처럼 연다.
        SwingUtilities.invokeAndWait(() -> terminalFrame[0].openContainerTerminal(inventory.get(1).servers().getFirst(),
                new com.infradesk.ssh.Container("c".repeat(64), "mysql", "mysql:8.4", "running", "Up 14 days",
                        "3306/tcp", 0.4, "412MiB / 23.4GiB", 1.7, ""),
                com.infradesk.ssh.DockerCommands.Console.MYSQL));
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> widgets = new java.util.ArrayList<>();
            collect(terminalFrame[0].getContentPane(), "JediTermWidget", widgets);
            var widget = (com.jediterm.terminal.ui.JediTermWidget) widgets.getLast();
            try {
                widget.getTtyConnector().write("show databases;\rselect count(*) from app.users;\r");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(1000);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(terminalFrame[0], terminalFrame[0].getContentPane(), 1280, 800, "terminal-mysql.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // 터미널 탭은 보고 있는 서버의 세션만 보여 준다: 세션이 없는 서버로 가면 빈 화면과 [접속]이 나오고,
        // 세션이 있는 서버로 돌아오면 보던 세션이 그대로 다시 보인다(다른 서버의 세션은 닫히지 않는다).
        com.infradesk.core.Server withSessions = inventory.get(1).servers().getFirst();
        com.infradesk.core.Server withoutSessions = inventory.getFirst().servers().getFirst();
        SwingUtilities.invokeAndWait(() -> terminalFrame[0].showServer(withoutSessions));
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(terminalFrame[0], terminalFrame[0].getContentPane(), 1280, 800, "terminal-empty.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        int[] counts = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            counts[0] = terminalFrame[0].terminalSessions(withoutSessions.id());
            counts[1] = terminalFrame[0].terminalSessions(withSessions.id());
            terminalFrame[0].showServer(withSessions);
        });
        Thread.sleep(300);
        System.out.println(counts[0] == 0 && counts[1] == 2
                ? "TERMINAL-PER-SERVER: OK (다른 서버의 세션 2개는 살아 있고, 이 서버는 세션이 없다)"
                : "TERMINAL-PER-SERVER: FAILED (세션이 없어야 할 서버 " + counts[0] + ", 있어야 할 서버 " + counts[1] + ")");
        // 데모 파일 트리의 SFTP 브라우저.
        // 파일 탭은 서버 화면 안의 패널이다. 시나리오를 창 하나에서 돌릴 수 있게 테스트용 창에 담는다.
        JDialog[] sftp = new JDialog[1];
        com.infradesk.ui.files.FilesTab[] filesTab = new com.infradesk.ui.files.FilesTab[1];
        SwingUtilities.invokeAndWait(() -> {
            filesTab[0] = new com.infradesk.ui.files.FilesTab(InfraDeskApp.demoTerminalService(),
                    inventory.getFirst().servers().getFirst());
            sftp[0] = new JDialog(terminalFrame[0], "files", false);
            sftp[0].setContentPane(filesTab[0]);
            sftp[0].setSize(1180, 700);
            sftp[0].setLocationRelativeTo(terminalFrame[0]);
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(sftp[0], sftp[0].getContentPane(), sftp[0].getWidth(), sftp[0].getHeight() - 28, "sftp.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // 내 컴퓨터에서 폴더 1개와 파일 2개를 골라 서버로 올리면 아래에 전송 대기열이 나타난다.
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> tables = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JTable", tables);
            javax.swing.JTable local = (javax.swing.JTable) tables.get(0);
            local.setRowSelectionInterval(0, 0);
            local.addRowSelectionInterval(3, 4);
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JButton", buttons);
            buttons.stream().map(b -> (javax.swing.AbstractButton) b)
                    .filter(b -> "서버로 올리기".equals(b.getText())).findFirst().orElseThrow().doClick();
        });
        Thread.sleep(2500);
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(sftp[0], sftp[0].getContentPane(), sftp[0].getWidth(), sftp[0].getHeight() - 28, "sftp-transfer.png");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // 전송 패널: 끝난 항목을 모두 지우면 패널이 접힌다.
        boolean[] panelState = new boolean[2];
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> panels = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "TransferPanel", panels);
            panelState[0] = panels.getFirst().isVisible();
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JButton", buttons);
            buttons.stream().map(x -> (javax.swing.AbstractButton) x)
                    .filter(x -> "끝난 항목 지우기".equals(x.getText())).findFirst().orElseThrow().doClick();
            panelState[1] = panels.getFirst().isVisible();
        });
        System.out.println(panelState[0] && !panelState[1] ? "TRANSFER-CLEAR: OK (모두 지우면 패널이 접혔다)"
                : "TRANSFER-CLEAR: FAILED (전 " + panelState[0] + ", 후 " + panelState[1] + ")");
        // 서버 파일 편집: 실제 편집기를 띄우지 않고, 받은 임시 파일에 저장하는 것을 흉내 낸 뒤
        // 서버에 다시 올라갔는지 확인한다.
        java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> edited = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            Class<?> editor = Class.forName("com.infradesk.ui.files.ExternalEditor");
            java.lang.reflect.Field hook = editor.getDeclaredField("launcherOverride");
            hook.setAccessible(true);
            hook.set(null, (java.util.function.Consumer<java.nio.file.Path>) edited::set);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        // 내 컴퓨터 파일 편집: 서버를 거치지 않고 그 파일 자체를 편집기로 연다("편집" 버튼은 왼쪽이 먼저 나온다).
        java.nio.file.Path localHome = java.nio.file.Path.of(InfraDeskApp.demoTerminalService().localFiles().home());
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> tables = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JTable", tables);
            javax.swing.JTable local = (javax.swing.JTable) tables.get(0);
            for (int row = 0; row < local.getRowCount(); row++) {
                if (local.getValueAt(row, 0).toString().contains("notes.txt")) {
                    local.setRowSelectionInterval(row, row);
                }
            }
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JButton", buttons);
            buttons.stream().map(b -> (javax.swing.AbstractButton) b)
                    .filter(b -> "편집".equals(b.getText())).findFirst().orElseThrow().doClick();
        });
        Thread.sleep(500);
        System.out.println(localHome.resolve("notes.txt").equals(edited.get())
                ? "LOCAL-EDIT: OK (내 컴퓨터 파일을 바로 열었다)" : "LOCAL-EDIT: FAILED (" + edited.get() + ")");
        edited.set(null);
        // 다운로드 버튼(더블클릭과 같은 동작)은 왼쪽 패널이 어느 폴더에 있든 다운로드 폴더(데모는 시작 폴더)로 받는다.
        try {
            java.lang.reflect.Field paneField = filesTab[0].getClass().getDeclaredField("localPane");
            paneField.setAccessible(true);
            Object leftPane = paneField.get(filesTab[0]);
            java.lang.reflect.Method open = leftPane.getClass().getDeclaredMethod("open", String.class);
            open.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    open.invoke(leftPane, localHome.resolve("Documents").toString());
                } catch (ReflectiveOperationException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> tables = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JTable", tables);
            javax.swing.JTable remote = (javax.swing.JTable) tables.get(1);
            for (int row = 0; row < remote.getRowCount(); row++) {
                if (remote.getValueAt(row, 0).toString().contains("README.md")) {
                    remote.setRowSelectionInterval(row, row);
                }
            }
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JButton", buttons);
            buttons.stream().map(b -> (javax.swing.AbstractButton) b)
                    .filter(x -> "내 컴퓨터로 받기".equals(x.getText())).findFirst().orElseThrow().doClick();
        });
        Thread.sleep(2500);
        // 새 전송이 시작되면 목록이 펼쳐진다. 접으면 머리 줄만 남고(목록은 그대로), 다시 펼칠 수 있다.
        boolean[] closeState = new boolean[5];
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> panels = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "TransferPanel", panels);
            var panel = (com.infradesk.ui.files.TransferPanel) panels.getFirst();
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JButton", buttons);
            java.util.function.Consumer<String> clickTip = tip -> buttons.stream().map(x -> (javax.swing.AbstractButton) x)
                    .filter(x -> tip.equals(x.getToolTipText())).findFirst().orElseThrow().doClick();
            closeState[0] = panel.isVisible() && panel.isExpanded();
            clickTip.accept("접기");
            closeState[1] = panel.isExpanded();
            closeState[2] = panel.isVisible();   // 머리 줄은 남는다
            clickTip.accept("펼치기");
            closeState[3] = panel.isExpanded();
            clickTip.accept("접기");
            closeState[4] = !panel.isExpanded();
        });
        boolean closeOk = closeState[0] && !closeState[1] && closeState[2] && closeState[3] && closeState[4];
        System.out.println(closeOk ? "TRANSFER-CLOSE: OK (접고, 다시 펼치고, 다시 접는다)"
                : "TRANSFER-CLOSE: FAILED " + java.util.Arrays.toString(closeState));
        // 패널을 X로 닫은 뒤, 이미 있는 파일을 덮어쓰며 다시 받는다(확인 창에서 "덮어쓰기"). 패널이 다시 나타나야 한다.
        SwingUtilities.invokeLater(() -> {
            List<java.awt.Component> tables = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JTable", tables);
            javax.swing.JTable remote = (javax.swing.JTable) tables.get(1);
            for (int row = 0; row < remote.getRowCount(); row++) {
                if (remote.getValueAt(row, 0).toString().contains("README.md")) {
                    remote.setRowSelectionInterval(row, row);
                }
            }
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JButton", buttons);
            buttons.stream().map(x -> (javax.swing.AbstractButton) x)
                    .filter(x -> "내 컴퓨터로 받기".equals(x.getText())).findFirst().orElseThrow().doClick();
        });
        boolean answered = false;
        for (int i = 0; i < 40 && !answered; i++) {
            Thread.sleep(100);
            boolean[] clicked = {false};
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window w : java.awt.Window.getWindows()) {
                    if (w.isShowing() && w instanceof JDialog d && d.isModal()) {
                        List<java.awt.Component> found = new java.util.ArrayList<>();
                        collect(d.getContentPane(), "JButton", found);
                        for (java.awt.Component c : found) {
                            if ("덮어쓰기".equals(((javax.swing.AbstractButton) c).getText())) {
                                ((javax.swing.AbstractButton) c).doClick();
                                clicked[0] = true;
                            }
                        }
                    }
                }
            });
            answered = clicked[0];
        }
        Thread.sleep(1500);
        boolean[] overwriteShown = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> panels = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "TransferPanel", panels);
            overwriteShown[0] = ((com.infradesk.ui.files.TransferPanel) panels.getFirst()).isExpanded();
        });
        System.out.println(answered && overwriteShown[0] ? "REOPEN-ON-OVERWRITE: OK (덮어쓰며 받으면 패널이 나타났다)"
                : "REOPEN-ON-OVERWRITE: FAILED (확인 창 응답 " + answered + ", 패널 보임 " + overwriteShown[0] + ")");
        boolean inHome = java.nio.file.Files.exists(localHome.resolve("README.md"));
        boolean inPaneFolder = java.nio.file.Files.exists(localHome.resolve("Documents").resolve("README.md"));
        System.out.println(inHome && !inPaneFolder ? "DOWNLOAD-DIR: OK (패널이 Documents에 있어도 다운로드 폴더로 받았다)"
                : "DOWNLOAD-DIR: FAILED (다운로드 폴더 " + inHome + ", 패널 폴더 " + inPaneFolder + ")");
        SwingUtilities.invokeAndWait(() -> {
            List<java.awt.Component> tables = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JTable", tables);
            javax.swing.JTable remote = (javax.swing.JTable) tables.get(1);
            for (int row = 0; row < remote.getRowCount(); row++) {
                if (remote.getValueAt(row, 0).toString().contains("README.md")) {
                    remote.setRowSelectionInterval(row, row);
                }
            }
            List<java.awt.Component> buttons = new java.util.ArrayList<>();
            collect(sftp[0].getContentPane(), "JButton", buttons);
            buttons.stream().map(b -> (javax.swing.AbstractButton) b)
                    .filter(b -> "편집".equals(b.getText())).reduce((first, second) -> second).orElseThrow().doClick();
        });
        Thread.sleep(1500);
        java.nio.file.Path tempCopy = edited.get();
        System.out.println(tempCopy == null ? "EDIT-LOOP: FAILED (편집기가 열리지 않음)" : "EDIT-LOOP: 임시 복사본을 열었다");
        if (tempCopy != null) {
            java.nio.file.Files.writeString(tempCopy, "# EDITED-BY-SNAPSHOT\n");
            java.nio.file.Files.setLastModifiedTime(tempCopy,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
            Thread.sleep(4500);
            var verify = InfraDeskApp.demoTerminalService();
            try (var rf = verify.openFiles(inventory.getFirst().servers().getFirst(), (h, pt, ty, fp) -> true)) {
                java.nio.file.Path back = java.nio.file.Files.createTempFile("edit-verify", ".md");
                rf.download(com.infradesk.ssh.RemoteFiles.join(rf.home(), "README.md"), back, n -> { });
                String content = java.nio.file.Files.readString(back);
                System.out.println(content.contains("EDITED-BY-SNAPSHOT") ? "EDIT-LOOP: OK (저장한 내용이 서버에 올라갔다)"
                        : "EDIT-LOOP: FAILED (서버 내용: " + content.strip() + ")");
            }
            // 두 번째 저장: 첫 업로드 뒤 서버 파일 상태를 다시 기록했는지 확인한다. 잘못되면 "서버에서
            // 바뀌었어요" 확인 창이 떠서 여기서 멈춘다.
            java.nio.file.Files.writeString(tempCopy, "# EDITED-TWICE\n");
            java.nio.file.Files.setLastModifiedTime(tempCopy,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
            Thread.sleep(4500);
            try (var rf = verify.openFiles(inventory.getFirst().servers().getFirst(), (h, pt, ty, fp) -> true)) {
                java.nio.file.Path back = java.nio.file.Files.createTempFile("edit-verify2", ".md");
                rf.download(com.infradesk.ssh.RemoteFiles.join(rf.home(), "README.md"), back, n -> { });
                String content = java.nio.file.Files.readString(back);
                System.out.println(content.contains("EDITED-TWICE") ? "EDIT-LOOP-2: OK (두 번째 저장도 올라갔다)"
                        : "EDIT-LOOP-2: FAILED (서버 내용: " + content.strip() + ")");
            }
            SwingUtilities.invokeAndWait(() -> {
                try {
                    write(sftp[0], sftp[0].getContentPane(), sftp[0].getWidth(), sftp[0].getHeight() - 28, "sftp-edit.png");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
        System.out.println("Snapshots written to " + DIR.getAbsolutePath());
        System.exit(0);
    }

    /** 모니터링 탭을 찍는다. 차트 3개가 세로로 쌓여 길어서 높이를 1100으로 키운다. */
    private static void snapshotMonitoring(String name, MainFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                write(frame, frame.getContentPane(), 1280, 1100, name);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            frame.getContentPane().setSize(1280, 800);
        });
    }

    private static java.awt.Component findText(Container root, String text) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.AbstractButton b && text.equals(b.getText())) {
                return c;
            }
            if (c instanceof Container inner) {
                var found = findText(inner, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static java.awt.Component find(Container root, String className) {
        for (java.awt.Component c : root.getComponents()) {
            if (c.getClass().getSimpleName().equals(className)) {
                return c;
            }
            if (c instanceof Container inner) {
                var found = find(inner, className);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void write(Window window, Container pane, int width, int height, String name) throws IOException {
        window.addNotify();
        pane.setSize(width, height);
        layoutAll(pane);
        BufferedImage img = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        pane.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(DIR, name));
    }

    /** 가짜 /proc 스트림이 150 ms마다 틱하는 데모 터미널. 실시간 스냅샷에서 차트가 가득 차게 한다. */
    private static com.infradesk.service.TerminalService fastLiveTerminalService() {
        return new com.infradesk.service.TerminalService(new com.infradesk.storage.InMemorySshSettingsStore(),
                new com.infradesk.storage.InMemorySecretStore(),
                new com.infradesk.demo.DemoShellConnector(Duration.ZERO, Duration.ofMillis(150)),
                new com.infradesk.storage.InMemorySavedCommandStore(com.infradesk.demo.DemoData.savedCommands()), true);
    }

    private static void collect(Container c, String simpleName, List<java.awt.Component> out) {
        for (var child : c.getComponents()) {
            if (child.getClass().getSimpleName().equals(simpleName)) {
                out.add(child);
            }
            if (child instanceof Container cc) {
                collect(cc, simpleName, out);
            }
        }
    }

    private static <T> T find(Container c, Class<T> type) {
        for (var child : c.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container cc) {
                T found = find(cc, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layoutAll(Container c) {
        c.doLayout();
        for (var child : c.getComponents()) {
            if (child instanceof Container cc) {
                layoutAll(cc);
            }
        }
    }
}
