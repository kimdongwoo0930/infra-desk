package com.infradesk.provider.oracle;

/** Keys in {@code Account.properties()} used by the Oracle provider. */
public final class OracleProperties {

    public static final String TENANCY_OCID = "tenancyOcid";
    public static final String USER_OCID = "userOcid";
    public static final String FINGERPRINT = "fingerprint";
    /** Optional; defaults to the tenancy (root compartment). */
    public static final String COMPARTMENT_OCID = "compartmentOcid";

    private OracleProperties() {
    }
}
