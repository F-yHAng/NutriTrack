package com.example.nutritrack

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestComponentTest {
    @Test
    fun declaredApplicationComponents_existInProductionSources() {
        val moduleDirectory = moduleDirectory()
        val manifest = File(moduleDirectory, "src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val componentTags = listOf("application", "activity")

        componentTags.forEach { tagName ->
            val nodes = document.getElementsByTagName(tagName)
            for (index in 0 until nodes.length) {
                val declaredName = nodes.item(index).attributes
                    .getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue
                    ?: continue
                val relativeClassName = declaredName
                    .removePrefix(APPLICATION_PACKAGE)
                    .removePrefix(".")
                val sourceFile = File(
                    moduleDirectory,
                    "src/main/java/${APPLICATION_PACKAGE.replace('.', '/')}/" +
                        "${relativeClassName.replace('.', '/')}.kt"
                )
                assertTrue(
                    "$tagName $declaredName is declared in the manifest but has no source class",
                    sourceFile.isFile
                )
            }
        }
    }

    private fun moduleDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        return if (File(workingDirectory, "src/main").isDirectory) {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APPLICATION_PACKAGE = "com.example.nutritrack"
    }
}
