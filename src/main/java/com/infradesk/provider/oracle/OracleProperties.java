package com.infradesk.provider.oracle;

/** Oracle provider가 쓰는 {@code Account.properties()}의 키. */
public final class OracleProperties {

    public static final String TENANCY_OCID = "tenancyOcid";
    public static final String USER_OCID = "userOcid";
    public static final String FINGERPRINT = "fingerprint";
    /** 선택 사항. 기본값은 테넌시(루트 컴파트먼트). */
    public static final String COMPARTMENT_OCID = "compartmentOcid";

    private OracleProperties() {
    }
}
