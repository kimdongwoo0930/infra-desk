package com.infradesk.ui.settings;

import com.infradesk.app.LaunchAtLogin;
import com.infradesk.ui.Async;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ToggleSwitch;

import java.nio.file.Path;
import java.util.Optional;
import javax.swing.JButton;

/**
 * 설정 "일반": 앱이 켜지고 닫힐 때의 동작. 지금 동작하는 것은 "로그인할 때 실행"뿐이고, 나머지(창을 닫으면 숨기기 끄기,
 * 시작 화면, 서버 목록 묶기, 단축키)는 자리만 만들어 두었다(준비 중). 창을 닫으면 숨기는 동작 자체는 이미 있지만
 * 끌 수 있는 설정이 없어서 켜진 상태로 비활성이다.
 */
public final class GeneralPage extends SettingsPage {

    public static final String ID = "general";

    private final ToggleSwitch login = Controls.toggle(false);
    private final SettingsRow loginRow;

    public GeneralPage(LaunchAtLogin launchAtLogin, boolean demoMode) {
        super(ID, "일반", "일반", "앱이 켜지고 닫힐 때의 동작");

        boolean supported = launchAtLogin != null && launchAtLogin.isSupported() && !demoMode;
        login.setSelected(launchAtLogin != null && launchAtLogin.isEnabled());
        login.setEnabled(supported);
        loginRow = new SettingsRow("로그인할 때 InfraDesk 실행",
                loginDescription(demoMode, launchAtLogin == null ? Optional.empty() : launchAtLogin.target()), login);
        login.addActionListener(e -> {
            boolean on = login.isSelected();
            login.setEnabled(false);
            Async.run(() -> {
                launchAtLogin.setEnabled(on);
                return null;
            }, ignored -> login.setEnabled(true), err -> {
                login.setSelected(!on);   // 못 바꿨으면 되돌린다
                login.setEnabled(true);
                loginRow.setDescription(Async.message(err));
            });
        });

        SettingsCard card = new SettingsCard();
        card.add(loginRow);
        // 준비 중: 창을 닫을 때 숨기는 동작은 지금도 하지만(메뉴 막대/알림 영역 아이콘이 있을 때) 끌 수 있는 설정은 없다.
        card.add(new SettingsRow("창을 닫으면 메뉴 막대로 숨기기", "끄면 창을 닫을 때 앱이 종료돼요",
                Controls.toggle(true)).comingSoon());
        card.add(new SettingsRow("시작 화면", "앱을 열 때 처음 보여줄 화면", Controls.choice(110, "개요")).comingSoon());
        card.add(new SettingsRow("서버 목록 묶기", "사이드바와 개요에서 서버를 묶는 방법", Controls.choice(120, "계정별")).comingSoon());
        JButton shortcuts = Buttons.secondary("단축키 모두 보기", null);
        card.add(new SettingsRow("단축키", "자주 쓰는 동작의 단축키 목록", shortcuts).comingSoon());
        addCard(card);
    }

    /** "로그인할 때 실행" 설명: 데모, 실행 대상을 찾았을 때, 못 찾았을 때. */
    static String loginDescription(boolean demoMode, Optional<Path> target) {
        if (demoMode) {
            return "데모 모드에서는 바꿀 수 없어요.";
        }
        return target.map(p -> "메뉴 막대에서 조용히 시작해요 · 실행 대상: " + p)
                .orElse("설치한 InfraDesk를 찾지 못했어요 (응용 프로그램 폴더에 설치하면 켤 수 있어요).");
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    ToggleSwitch loginSwitch() {
        return login;
    }

    String loginDescriptionText() {
        return loginRow.descriptionText();
    }
}
