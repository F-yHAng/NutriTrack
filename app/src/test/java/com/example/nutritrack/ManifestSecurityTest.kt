package com.example.nutritrack

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestSecurityTest {
    @Test
    fun mergedDebugManifest_disablesBackupAndCleartextTraffic() {
        val manifest = mergedDebugManifest()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val application = document.getElementsByTagName("application").item(0)

        assertEquals("false", application.androidAttribute("allowBackup"))
        assertEquals("false", application.androidAttribute("usesCleartextTraffic"))
        assertFalse(application.hasAndroidAttribute("dataExtractionRules"))
        assertFalse(application.hasAndroidAttribute("fullBackupContent"))
        assertFalse(application.hasAndroidAttribute("networkSecurityConfig"))
    }

    private fun mergedDebugManifest(): File {
        val moduleDirectory = moduleDirectory()
        val candidates = listOf(
            File(
                moduleDirectory,
                "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
            ),
            File(
                moduleDirectory,
                "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
            )
        )
        return candidates.firstOrNull(File::isFile).also {
            assertTrue("Debug merged manifest was not generated", it != null)
        }!!
    }

    private fun moduleDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return if (File(workingDirectory, "src/main").isDirectory) {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
    }

    private fun org.w3c.dom.Node.androidAttribute(name: String): String =
        attributes.getNamedItemNS(ANDROID_NAMESPACE, name)?.nodeValue.orEmpty()

    private fun org.w3c.dom.Node.hasAndroidAttribute(name: String): Boolean =
        attributes.getNamedItemNS(ANDROID_NAMESPACE, name) != null

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
