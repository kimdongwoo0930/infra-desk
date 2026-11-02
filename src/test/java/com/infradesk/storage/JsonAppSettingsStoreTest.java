package com.infradesk.storage;

import com.infradesk.core.AppSettings;
import com.infradesk.core.OverviewView;
import com.infradesk.core.ServerGrouping;
import com.infradesk.service.AppSettingsService;
import com.infradesk.service.UpdateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAppSettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void withoutAFileTheDefaultsAreUsed() {
        AppSettings settings = new JsonAppSettingsStore(dir).load();

        assertEquals(AppSettings.DEFAULT, settings);
        assertNull(settings.startServerId());
        assertEquals(ServerGrouping.ACCOUNT, settings.grouping());
    }

    @Test
    void savedValuesComeBackAfterRestart() {
        new JsonAppSettingsStore(dir).save(new AppSettings("server-1", ServerGrouping.STATUS));

        AppSettings loaded = new JsonAppSettingsStore(dir).load();

        assertEquals("server-1", loaded.startServerId());
        assertEquals(ServerGrouping.STATUS, loaded.grouping());
    }

    @Test
    void clearingTheStartServerRemovesItFromTheFile() throws Exception {
        JsonAppSettingsStore store = new JsonAppSettingsStore(dir);
        store.save(new AppSettings("server-1", ServerGrouping.NONE));

        store.save(new AppSettings(null, ServerGrouping.NONE));

        assertNull(store.load().startServerId());
        assertFalse(Files.readString(dir.resolve("app-settings.json")).contains("startServerId"));
    }

    @Test
    void savingKeepsTheUpdateCheckValueInTheSameFile() {
        UpdateService updates = new UpdateService(HttpClient.newHttpClient(), URI.create("http://x"), "a",
                dir.resolve("app-settings.json"));
        updates.setAutoCheck(false);

        new JsonAppSettingsStore(dir).save(new AppSettings("s", ServerGrouping.STATUS));

        assertFalse(updates.autoCheck(), "시작 화면을 저장해도 업데이트 자동 확인 값이 사라지면 안 된다");
    }

    @Test
    void changingTheUpdateCheckKeepsTheGeneralValues() {
        JsonAppSettingsStore store = new JsonAppSettingsStore(dir);
        store.save(new AppSettings("s", ServerGrouping.NONE));
        UpdateService updates = new UpdateService(HttpClient.newHttpClient(), URI.create("http://x"), "a",
                dir.resolve("app-settings.json"));

        updates.setAutoCheck(false);

        AppSettings loaded = store.load();
        assertEquals("s", loaded.startServerId());
        assertEquals(ServerGrouping.NONE, loaded.grouping());
    }

    @Test
    void aBrokenFileFallsBackToDefaultsInsteadOfStoppingTheApp() throws Exception {
        Files.writeString(dir.resolve("app-settings.json"), "{ 이건 JSON이 아니다");
        JsonAppSettingsStore store = new JsonAppSettingsStore(dir);

        assertEquals(AppSettings.DEFAULT, store.load());

        store.save(new AppSettings("s", ServerGrouping.STATUS));   // 다시 저장하면 새 파일로 고쳐진다
        assertEquals("s", store.load().startServerId());
    }

    @Test
    void anUnknownGroupingNameFallsBackToByAccount() throws Exception {
        Files.writeString(dir.resolve("app-settings.json"), "{\"grouping\":\"BY_MOON\",\"startServerId\":\"x\"}");

        AppSettings loaded = new JsonAppSettingsStore(dir).load();

        assertEquals(ServerGrouping.ACCOUNT, loaded.grouping());
        assertEquals("x", loaded.startServerId());
    }

    @Test
    void theOverviewViewIsSavedAndStartsAsCards() {
        JsonAppSettingsStore store = new JsonAppSettingsStore(dir);
        assertEquals(OverviewView.CARDS, store.load().overviewView());

        store.save(AppSettings.DEFAULT.withOverviewView(OverviewView.LIST));

        assertEquals(OverviewView.LIST, new JsonAppSettingsStore(dir).load().overviewView());
    }

    @Test
    void savingOtherValuesKeepsTheOverviewView() {
        JsonAppSettingsStore store = new JsonAppSettingsStore(dir);
        AppSettingsService service = new AppSettingsService(store);
        service.update(s -> s.withOverviewView(OverviewView.LIST));

        service.update(s -> s.withGrouping(ServerGrouping.STATUS).withStartServerId("x"));

        AppSettings loaded = store.load();
        assertEquals(OverviewView.LIST, loaded.overviewView());
        assertEquals(ServerGrouping.STATUS, loaded.grouping());
        assertEquals("x", loaded.startServerId());
    }

    @Test
    void anUnknownOverviewViewFallsBackToCards() throws Exception {
        Files.writeString(dir.resolve("app-settings.json"), "{\"overviewView\":\"GALLERY\"}");

        assertEquals(OverviewView.CARDS, new JsonAppSettingsStore(dir).load().overviewView());
    }

    @Test
    void publicIpsAreHiddenByDefault() {
        assertTrue(AppSettings.DEFAULT.hidePublicIp());
        assertTrue(new JsonAppSettingsStore(dir).load().hidePublicIp(), "설정 파일이 없어도 가려야 한다");
    }

    @Test
    void turningHidingOffIsSavedAndKeptWhenOtherValuesChange() {
        JsonAppSettingsStore store = new JsonAppSettingsStore(dir);
        AppSettingsService service = new AppSettingsService(store);

        service.update(s -> s.withHidePublicIp(false));
        service.update(s -> s.withGrouping(ServerGrouping.NONE));

        assertFalse(new JsonAppSettingsStore(dir).load().hidePublicIp());
    }

    @Test
    void anOldFileWithoutTheValueOrWithAnOddOneStillHidesTheIp() throws Exception {
        Files.writeString(dir.resolve("app-settings.json"), "{\"grouping\":\"STATUS\"}");
        assertTrue(new JsonAppSettingsStore(dir).load().hidePublicIp());

        Files.writeString(dir.resolve("app-settings.json"), "{\"hidePublicIp\":\"maybe\"}");
        assertTrue(new JsonAppSettingsStore(dir).load().hidePublicIp(), "모르는 값이면 안전한 쪽(가림)");
    }

    @Test
    void aBlankStartServerCountsAsNone() {
        assertNull(new AppSettings("  ", ServerGrouping.ACCOUNT).startServerId());
    }

    // ── AppSettingsService ──────────────────────────────────────

    @Test
    void theServiceSavesAndTellsListenersAboutAChange() {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        AppSettingsService service = new AppSettingsService(store);
        List<AppSettings> heard = new ArrayList<>();
        service.onChange(heard::add);

        service.update(s -> s.withGrouping(ServerGrouping.STATUS));

        assertEquals(ServerGrouping.STATUS, service.current().grouping());
        assertEquals(ServerGrouping.STATUS, store.load().grouping());
        assertEquals(1, heard.size());
    }

    @Test
    void theServiceStaysQuietWhenNothingChanged() {
        AppSettingsService service = new AppSettingsService(new InMemoryAppSettingsStore());
        List<AppSettings> heard = new ArrayList<>();
        service.onChange(heard::add);

        service.update(s -> s.withGrouping(ServerGrouping.ACCOUNT));

        assertTrue(heard.isEmpty());
    }

    @Test
    void aFailedSaveLeavesTheValueAsItWas() {
        AppSettingsStore failing = new AppSettingsStore() {
            @Override
            public AppSettings load() {
                return AppSettings.DEFAULT;
            }

            @Override
            public void save(AppSettings settings) {
                throw new StorageException("저장하지 못했어요", null);
            }
        };
        AppSettingsService service = new AppSettingsService(failing);
        List<AppSettings> heard = new ArrayList<>();
        service.onChange(heard::add);

        assertThrows(StorageException.class, () -> service.update(s -> s.withGrouping(ServerGrouping.NONE)));

        assertEquals(ServerGrouping.ACCOUNT, service.current().grouping());
        assertTrue(heard.isEmpty());
    }
}
