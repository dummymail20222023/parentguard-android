plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// CI Static Verification Task
tasks.register("verifyPrivacyAndManifestInvariants") {
    group = "verification"
    description = "Verifies manifest and source invariants for ParentGuard build."
    doLast {
        val forbiddenPermissions = listOf(
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.QUERY_ALL_PACKAGES"
        )
        val manifests = fileTree(rootDir) { include("**/AndroidManifest.xml") }
        manifests.forEach { manifestFile ->
            val content = manifestFile.readText()
            forbiddenPermissions.forEach { perm ->
                if (content.contains(perm)) {
                    throw GradleException("VIOLATION: Forbidden permission '$perm' found in ${manifestFile.path}")
                }
            }
        }
        println("SUCCESS: All ParentGuard build invariants verified.")
    }
}
