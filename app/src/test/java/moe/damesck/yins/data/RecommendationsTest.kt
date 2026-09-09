package moe.damesck.yins.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationsTest {
    @Test
    fun messagingAppsGetFullAccess() {
        assertEquals(AppKind.TRUSTED, Recommendations.kindOf("com.tencent.mobileqq"))
        assertEquals(AppKind.TRUSTED, Recommendations.kindOf("com.tencent.mm"))
        assertEquals(Mode.FULL, Recommendations.mode(AppKind.TRUSTED))
    }

    @Test
    fun lifestyleAppsGetPartialAccess() {
        assertEquals(AppKind.TEMPORARY, Recommendations.kindOf("com.dianping.v1"))
        assertEquals(AppKind.TEMPORARY, Recommendations.kindOf("com.sankuai.meituan"))
        assertEquals(Mode.PARTIAL, Recommendations.mode(AppKind.TEMPORARY))
    }

    @Test
    fun unknownAppsGetBlankPass() {
        assertNull(Recommendations.kindOf("com.example.unknown"))
        assertEquals(Mode.BLANK, Recommendations.mode(AppKind.UNKNOWN))
    }
}
