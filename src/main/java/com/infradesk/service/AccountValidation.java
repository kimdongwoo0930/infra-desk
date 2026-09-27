package com.infradesk.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Format checks for the add-account form. Returns Korean messages; empty list means valid. */
public final class AccountValidation {

    private static final Pattern FINGERPRINT = Pattern.compile("^([0-9a-fA-F]{2}:){15}[0-9a-fA-F]{2}$");

    private AccountValidation() {
    }

    public static List<String> oracle(String displayName, String tenancyOcid, String userOcid,
                                      String fingerprint, String region, String privateKeyPem) {
        List<String> errors = new ArrayList<>();
        if (isBlank(displayName)) {
            errors.add("표시 이름을 입력하세요.");
        }
        if (isBlank(tenancyOcid) || !tenancyOcid.strip().startsWith("ocid1.tenancy.")) {
            errors.add("Tenancy OCID는 'ocid1.tenancy.'로 시작해야 해요.");
        }
        if (isBlank(userOcid) || !userOcid.strip().startsWith("ocid1.user.")) {
            errors.add("User OCID는 'ocid1.user.'로 시작해야 해요.");
        }
        if (isBlank(fingerprint) || !FINGERPRINT.matcher(fingerprint.strip()).matches()) {
            errors.add("Fingerprint 형식이 달라요 (예: 12:34:...:ef, 16쌍).");
        }
        if (isBlank(region)) {
            errors.add("리전을 선택하세요.");
        }
        if (privateKeyPem != null && !privateKeyPem.contains("PRIVATE KEY-----")) {
            errors.add("API 개인키 파일이 PEM 형식이 아니에요.");
        }
        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
