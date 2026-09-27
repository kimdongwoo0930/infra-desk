package com.infradesk.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountValidationTest {

    private static final String FP = "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff";
    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----";

    @Test
    void validInput() {
        assertTrue(AccountValidation.oracle("A", "ocid1.tenancy.oc1..x", "ocid1.user.oc1..x", FP, "ap-seoul-1", PEM).isEmpty());
    }

    @Test
    void nullKeyIsAllowedForDemo() {
        assertTrue(AccountValidation.oracle("A", "ocid1.tenancy.oc1..x", "ocid1.user.oc1..x", FP, "ap-seoul-1", null).isEmpty());
    }

    @Test
    void reportsEachProblem() {
        var errors = AccountValidation.oracle(" ", "ocid1.user.oc1..x", "nope", "12:34", "", "not a key");
        assertEquals(6, errors.size());
    }
}
