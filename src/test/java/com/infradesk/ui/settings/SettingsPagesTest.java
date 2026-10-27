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
        SettingsScreen screen = new SettingsScreen(InfraDeskApp.demoAlertService(), true, null, null, null, () -> { });
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
