package com.swufe.homework

import com.swufe.homework.util.Version
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun newerPatch() = assertTrue(Version.isNewer("v0.1.1", "0.1.0"))
    @Test fun same() = assertFalse(Version.isNewer("v0.1.0", "0.1.0"))
    @Test fun older() = assertFalse(Version.isNewer("v0.0.9", "0.1.0"))
    @Test fun numericNotLexical() = assertTrue(Version.isNewer("v0.10.0", "0.9.9"))
    @Test fun missingParts() = assertFalse(Version.isNewer("v1.0", "1.0.0"))
    @Test fun preReleaseLower() = assertFalse(Version.isNewer("v1.0.0-beta", "1.0.0"))
    @Test fun releaseAfterPre() = assertTrue(Version.isNewer("v1.0.0", "1.0.0-rc1"))
}
