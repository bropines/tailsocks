package io.github.bropines.tailscaled.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatPackageTest {
    private val release = "io.github.bropines.tailscaled"

    @Test fun releaseAndDevAreOneApp() {
        assertTrue(BackupFormat.samePackage(release, "$release.dev"))
        assertTrue(BackupFormat.samePackage("$release.dev", release))
        assertTrue(BackupFormat.samePackage(release, release))
    }

    @Test fun otherPackagesAreRefused() {
        assertFalse(BackupFormat.samePackage("com.example.fork", release))
        assertFalse(BackupFormat.samePackage("$release.devtools", release))
    }
}
