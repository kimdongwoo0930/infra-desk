package com.infradesk.ui.files;

import java.util.Set;

/**
 * 같은 폴더에 복사해 붙여넣을 때 쓸 새 이름을 정한다(Finder처럼): {@code report.txt} → {@code report 복사.txt},
 * 또 있으면 {@code report 복사 2.txt}. 폴더나 확장자가 없는 이름은 뒤에 붙인다({@code data} → {@code data 복사}).
 */
final class CopyNames {

    private CopyNames() {
    }

    /**
     * @param name      원래 이름
     * @param directory 폴더인가(폴더 이름의 점은 확장자가 아니다)
     * @param existing  그 폴더에 이미 있는 이름들(대소문자를 구분한다)
     */
    static String unique(String name, boolean directory, Set<String> existing) {
        int dot = directory ? -1 : name.lastIndexOf('.');
        // 맨 앞의 점(.bashrc)은 확장자가 아니라 이름의 일부다.
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String candidate = base + " 복사" + ext;
        for (int n = 2; existing.contains(candidate); n++) {
            candidate = base + " 복사 " + n + ext;
        }
        return candidate;
    }
}
