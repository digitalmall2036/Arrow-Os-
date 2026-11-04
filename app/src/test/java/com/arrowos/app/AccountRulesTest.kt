package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccountRulesTest {

    @Test fun emailMustLookLikeAnEmail() {
        assertNull(AccountRules.emailProblem("mj@gmail.com"))
        assertNull(AccountRules.emailProblem("  Mj@Gmail.COM "))
        assertNotNull(AccountRules.emailProblem(""))
        assertNotNull(AccountRules.emailProblem("mj"))
        assertNotNull(AccountRules.emailProblem("mj@gmail"))
        assertNotNull(AccountRules.emailProblem("m j@gmail.com"))
    }

    @Test fun emailIsTrimmedAndLowercased() {
        assertEquals("mj@gmail.com", AccountRules.cleanEmail("  Mj@Gmail.COM "))
    }

    @Test fun passwordNeedsSixCharacters() {
        assertNotNull(AccountRules.passwordProblem(""))
        assertNotNull(AccountRules.passwordProblem("12345"))
        assertNull(AccountRules.passwordProblem("123456"))
    }
}
