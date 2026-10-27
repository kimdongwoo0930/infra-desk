package com.infradesk.ui.settings;

import com.infradesk.alert.AlertService;
import com.infradesk.app.InfraDeskApp;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsPagesTest {

    private static SettingsScreen screen(Runnable onBack) {
        SettingsScreen screen = new SettingsScreen(InfraDeskApp.demoAlertService(), true, null, null, null, () -> { },
                ConnectionPage.Source.none());
        screen.onBack(onBack);
        return screen;
    }

    // ── 화면 틀 ─────────────────────────────────────────────────

    @Test
    void menuHasTheFiveItemsWithoutTheAgent() {
        assertEquals(List.of("일반", "알림", "보안·안전", "연결·조회", "업데이트·정보"), screen(() -> { }).menuLabels());
    }

    @Test
    void startsOnGeneralAndCanJumpToAnotherPage() {
        SettingsScreen screen = screen(() -> { });
        assertEquals(GeneralPage.ID, screen.shownId());

        screen.show(AlertsPage.ID);

        assertEquals(AlertsPage.ID, screen.shownId());
    }

    @Test
    void anUnknownPageNameChangesNothing() {
        SettingsScreen screen = screen(() -> { });
        screen.show(AboutPage.ID);

        screen.show("agent");

        assertEquals(AboutPage.ID, screen.shownId());
    }

    // ── 일반 ────────────────────────────────────────────────────

    @Test
    void loginDescriptionExplainsTheDemoAndMissingTargetCases() {
        assertEquals("데모 모드에서는 바꿀 수 없어요.", GeneralPage.loginDescription(true, Optional.of(Path.of("/x"))));
        assertTrue(GeneralPage.loginDescription(false, Optional.of(Path.of("/Applications/InfraDesk.app")))
                .endsWith("실행 대상: /Applications/InfraDesk.app"));
        assertTrue(GeneralPage.loginDescription(false, Optional.empty()).contains("찾지 못했어요"));
    }

    @Test
    void loginSwitchIsDisabledWhenThereIsNoLauncherOrInDemo() {
        GeneralPage page = new GeneralPage(null, false);

        assertFalse(page.loginSwitch().isEnabled());
        assertFalse(page.loginSwitch().isSelected());
    }

    // ── 알림 ────────────────────────────────────────────────────

    @Test
    void aWebhookThatIsNotADiscordUrlIsRejected() {
        assertNotNull(AlertsPage.webhookProblem("http://example.com/x", false, false, false));
        assertNull(AlertsPage.webhookProblem("https://discord.com/api/webhooks/123/abc", false, false, false));
    }

    @Test
    void aWebhookIsRequiredOnlyWhenNeededAndNotInDemo() {
        assertNotNull(AlertsPage.webhookProblem(null, true, false, false), "켜거나 테스트하려면 웹훅이 있어야 한다");
        assertNull(AlertsPage.webhookProblem(null, true, true, false), "이미 저장된 웹훅이 있으면 괜찮다");
        assertNull(AlertsPage.webhookProblem(null, true, false, true), "데모는 실제로 보내지 않는다");
        assertNull(AlertsPage.webhookProblem(null, false, false, false), "필요 없으면 비어 있어도 된다");
    }

    @Test
    void placeholderTellsWhetherAWebhookIsAlreadySaved() {
        assertTrue(AlertsPage.placeholder(true).contains("저장된 웹훅"));
        assertTrue(AlertsPage.placeholder(false).startsWith("https://discord.com"));
    }

    @Test
    void alertControlsAreDisabledWhileTheAlertFeatureIsNotAvailable() {
        if (AlertService.AVAILABLE) {
            return;   // 기능이 열린 빌드에서는 해당 없음
        }
        AlertsPage page = new AlertsPage(InfraDeskApp.demoAlertService(), true, () -> { });

        assertFalse(page.enabledSwitch().isEnabled());
        assertFalse(page.webhookField().isEnabled());
        assertFalse(page.cpuHighBox().isEnabled());
        assertFalse(page.testButton().isEnabled());
    }

    // ── 연결·조회 ───────────────────────────────────────────────

    @Test
    void durationsReadInTheLargestExactUnit() {
        assertEquals("30초", Controls.duration(Duration.ofSeconds(30)));
        assertEquals("5초", Controls.duration(Duration.ofSeconds(5)));
        assertEquals("1분", Controls.duration(Duration.ofMinutes(1)));
        assertEquals("10분", Controls.duration(Duration.ofMinutes(10)));
        assertEquals("12시간", Controls.duration(Duration.ofHours(12)));
        assertEquals("90초", Controls.duration(Duration.ofSeconds(90)), "딱 떨어지지 않으면 초로");
    }

    // ── 연결·조회: 열린 연결 끊기 ───────────────────────────────

    private static com.infradesk.ssh.OpenConnection connection(String id, com.infradesk.ssh.Purpose purpose, int active) {
        return new com.infradesk.ssh.OpenConnection(id, purpose, java.time.Instant.now(), java.time.Instant.now(), active);
    }

    /** 끊는 요청을 기록하고, 끊은 수는 정해 둔 값으로 돌려주는 가짜. */
    private static final class RecordingDisconnector implements ConnectionPage.Disconnector {
        final List<String> asked = new java.util.ArrayList<>();
        int closedByAll;

        @Override
        public boolean disconnect(String serverId, com.infradesk.ssh.Purpose purpose, boolean force) {
            asked.add(serverId + "/" + purpose + "/" + (force ? "force" : "idle"));
            return true;
        }

        @Override
        public int disconnectAll(boolean force) {
            asked.add("all/" + (force ? "force" : "idle"));
            return closedByAll;
        }
    }

    private static ConnectionPage page(List<com.infradesk.ssh.OpenConnection> open, ConnectionPage.Disconnector d) {
        return new ConnectionPage(new ConnectionPage.Source(() -> open, () -> java.util.Map.of("a", "서버 A"), () -> 0, d));
    }

    @Test
    void eachOpenConnectionHasADisconnectButtonAndOnlyOpenOnesDo() {
        ConnectionPage page = page(List.of(connection("a", com.infradesk.ssh.Purpose.QUERY, 0),
                connection("a", com.infradesk.ssh.Purpose.TRANSFER, 1)), new RecordingDisconnector());

        assertTrue(page.disconnectButton("a", com.infradesk.ssh.Purpose.QUERY).isEnabled());
        assertTrue(page.disconnectButton("a", com.infradesk.ssh.Purpose.TRANSFER).isEnabled(),
                "쓰는 중인 연결도 확인을 거쳐 강제로 끊을 수 있다");
        assertNull(page.disconnectButton("a", com.infradesk.ssh.Purpose.FILES), "열려 있지 않은 칸에는 버튼이 없다");
    }

    @Test
    void anIdleConnectionIsClosedWithoutAskingAndTheResultIsShown() {
        RecordingDisconnector d = new RecordingDisconnector();
        ConnectionPage page = page(List.of(connection("a", com.infradesk.ssh.Purpose.FILES, 0)), d);
        page.answerWith(msg -> {
            throw new AssertionError("놀고 있는 연결은 묻지 않고 끊는다");
        }, busy -> 2);

        page.disconnectButton("a", com.infradesk.ssh.Purpose.FILES).doClick();

        assertEquals(List.of("a/FILES/idle"), d.asked);
        assertEquals("서버 A의 파일 연결을 끊었어요", page.resultText());
    }

    @Test
    void aBusyConnectionIsForcedOnlyAfterTheUserConfirms() {
        RecordingDisconnector d = new RecordingDisconnector();
        ConnectionPage page = page(List.of(connection("a", com.infradesk.ssh.Purpose.TRANSFER, 1)), d);

        page.answerWith(msg -> false, busy -> 2);
        page.disconnectButton("a", com.infradesk.ssh.Purpose.TRANSFER).doClick();
        assertEquals(List.of(), d.asked, "취소하면 아무것도 끊지 않는다");

        List<String> questions = new java.util.ArrayList<>();
        page.answerWith(msg -> questions.add(msg), busy -> 2);
        page.disconnectButton("a", com.infradesk.ssh.Purpose.TRANSFER).doClick();
        assertEquals(List.of("a/TRANSFER/force"), d.asked, "확인하면 강제로 끊는다");
        assertTrue(questions.getFirst().contains("전송 중인 파일"), "무엇이 어떻게 되는지 알려 준다");
    }

    @Test
    void closeAllAsksOnlyWhenSomethingIsInUseAndHonoursTheChoice() {
        // 쓰는 중인 연결이 없으면 묻지 않고 놀고 있는 것을 끊는다
        RecordingDisconnector idleOnly = new RecordingDisconnector();
        idleOnly.closedByAll = 2;
        ConnectionPage quiet = page(List.of(connection("a", com.infradesk.ssh.Purpose.QUERY, 0)), idleOnly);
        quiet.answerWith(msg -> false, busy -> {
            throw new AssertionError("쓰는 중인 연결이 없으면 묻지 않는다");
        });
        quiet.closeAllButton().doClick();
        assertEquals(List.of("all/idle"), idleOnly.asked);
        assertEquals("연결 2개를 끊었어요", quiet.resultText());

        // 쓰는 중인 연결이 있으면 묻는다: 0 모두 끊기 / 1 놀고 있는 것만 / 그 밖은 취소
        for (int choice : new int[] {0, 1, 2}) {
            RecordingDisconnector d = new RecordingDisconnector();
            d.closedByAll = 1;
            ConnectionPage page = page(List.of(connection("a", com.infradesk.ssh.Purpose.TRANSFER, 1)), d);
            int[] askedAbout = {0};
            page.answerWith(msg -> false, busy -> {
                askedAbout[0] = busy;
                return choice;
            });

            page.closeAllButton().doClick();

            assertEquals(1, askedAbout[0], "쓰는 중인 연결 수를 알려 준다");
            assertEquals(choice == 0 ? List.of("all/force") : choice == 1 ? List.of("all/idle") : List.of(), d.asked);
        }
    }

    @Test
    void closeAllMessageSaysHowManyWereClosedAndHowManyWereLeft() {
        assertEquals("끊을 연결이 없어요", ConnectionPage.closeAllMessage(0, 0));
        assertEquals("연결 2개를 끊었어요", ConnectionPage.closeAllMessage(2, 0));
        assertEquals("연결 1개를 끊었어요 · 쓰는 중인 1개는 그대로 뒀어요", ConnectionPage.closeAllMessage(1, 1));
    }

    @Test
    void refreshingShowsConnectionsThatOpenedInTheMeantime() {
        List<com.infradesk.ssh.OpenConnection> now = new java.util.ArrayList<>();
        ConnectionPage page = page(now, new RecordingDisconnector());
        assertNull(page.disconnectButton("a", com.infradesk.ssh.Purpose.QUERY));

        now.add(connection("a", com.infradesk.ssh.Purpose.QUERY, 0));
        page.refresh();

        assertTrue(page.disconnectButton("a", com.infradesk.ssh.Purpose.QUERY).isEnabled(),
                "새로 열린 연결이 다시 읽자마자 표에 보인다");
    }

    // ── 설정 행 ─────────────────────────────────────────────────

    @Test
    void aComingSoonRowDisablesItsControl() {
        javax.swing.JToggleButton control = Controls.toggle(true);

        new SettingsRow("제목", "설명", control).comingSoon();

        assertFalse(control.isEnabled());
    }

    @Test
    void aRowKeepsItsTitleAndDescription() {
        SettingsRow row = new SettingsRow("로그인할 때 실행", "메뉴 막대에서 시작해요", null);

        assertEquals("로그인할 때 실행", row.titleText());
        assertEquals("메뉴 막대에서 시작해요", row.descriptionText());
    }
}
