package io.openwallet.demo

import io.openwallet.demo.update.ReleaseVersion
import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    private fun version(value: String) = requireNotNull(ReleaseVersion.parse(value))

    @Test fun comparesNumbersInsteadOfStrings() {
        assertTrue(version("v0.10.0") > version("0.9.9"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertTrue(version("0.1.0") > version("0.1.0-demo"))
        assertTrue(version("0.1.0-demo") > version("0.0.9"))
        assertEquals(0, version("V1.2.3+build.8").compareTo(version("1.2.3+build.9")))
    }

    @Test fun followsSemverPrereleaseOrder() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", version(a) < version(b)) }
    }

    @Test fun rejectsAmbiguousOrMalformedVersions() {
        listOf("", "latest", "1.2", "01.2.3", "1.2.3.4", "1.2.3-01", "1.2.3-", "1.2.3+", "1.2.3/other").forEach {
            assertNull(it, ReleaseVersion.parse(it))
        }
    }
}
