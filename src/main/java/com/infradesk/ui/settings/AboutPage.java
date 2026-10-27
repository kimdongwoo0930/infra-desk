package com.infradesk.ui.settings;

import com.infradesk.service.UpdateService;
import com.infradesk.ui.Async;
import com.infradesk.ui.ErrorReporter;
import com.infradesk.ui.LicensesDialog;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * 설정 "업데이트·정보": 버전과 업데이트 확인, 새 빌드 자동 확인, 업데이트 파일 확인 설명, 로그 폴더, 오픈소스 라이선스.
 * 바꾼 값은 바로 저장된다.
 */
public final class AboutPage extends SettingsPage {

    public static final String ID = "about";

    private final JLabel result = new JLabel(" ");
    private final ToggleSwitch autoCheck = Controls.toggle(false);

    /**
     * @param updates  업데이트 서비스. null이면(개발 실행 등) 업데이트 관련 컨트롤이 비활성이다
     * @param checkNow 업데이트 확인을 실행하고 결과를 {@link #showUpdateResult}로 알린다
     */
    public AboutPage(UpdateService updates, boolean demoMode, Runnable checkNow) {
        super(ID, "업데이트·정보", "업데이트·정보", "버전과 업데이트, 문제를 찾을 때 필요한 것");

        boolean canUpdate = updates != null && !demoMode;
        JButton check = Buttons.secondary("업데이트 확인", "refresh");
        check.setEnabled(canUpdate && checkNow != null);
        check.addActionListener(e -> {
            result.setText("확인하는 중…");
            result.setForeground(Theme.TEXT_MUTED);
            checkNow.run();
        });
        autoCheck.setSelected(updates != null && updates.autoCheck());
        autoCheck.setEnabled(canUpdate);
        autoCheck.addActionListener(e -> {
            boolean on = autoCheck.isSelected();
            Async.run(() -> {
                updates.setAutoCheck(on);
                return null;
            }, ignored -> { }, err -> {
                autoCheck.setSelected(!on);   // 저장하지 못했으면 되돌린다
                showUpdateResult(Async.message(err), false);
            });
        });
        JButton logs = Buttons.secondary("로그 폴더 열기", "folder");
        logs.addActionListener(e -> ErrorReporter.openLogFolder(com.infradesk.storage.AppPaths.logDir()));
        JButton licenses = Buttons.secondary("라이선스 보기", null);
        licenses.addActionListener(e -> new LicensesDialog(SwingUtilities.getWindowAncestor(this)).setVisible(true));

        SettingsCard card = new SettingsCard();
        card.add(new SettingsRow("InfraDesk " + com.infradesk.app.BuildInfo.current().display(),
                "빌드 번호와 커밋은 앱 정보에서 볼 수 있어요", check));
        card.add(new SettingsRow("새 버전 자동 확인", "시작할 때, 그리고 6시간마다", autoCheck));
        card.add(new SettingsRow("업데이트 파일 확인", "받은 파일이 릴리스의 해시와 같아야 설치해요",
                Controls.value("SHA256SUMS.txt 대조 · 자가 점검 후 교체")));
        card.add(new SettingsRow("로그 폴더", "키·웹훅·공인 IP·OCID는 가려서 기록해요", logs));
        card.add(new SettingsRow("오픈소스 라이선스", "앱에 포함된 라이브러리", licenses));
        addCard(card);

        result.setForeground(Theme.TEXT_MUTED);
        result.setBorder(BorderFactory.createEmptyBorder(12, 4, 0, 0));
        addCard(result);
    }

    /** 이 화면에서 시작한 업데이트 확인의 결과를 보여 준다(EDT에서 호출). */
    public void showUpdateResult(String message, boolean newer) {
        result.setText(message);
        result.setForeground(newer ? Theme.RUNNING_BADGE_TEXT : Theme.TEXT_MUTED);
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    String resultText() {
        return result.getText();
    }

    ToggleSwitch autoCheckSwitch() {
        return autoCheck;
    }
}
