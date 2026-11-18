package com.fahadali.oneos.core.dex

import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleClassA = """
        .class public Lcom/android/server/SampleService;
        .super Ljava/lang/Object;

        .field private static final FIRST_APPLICATION_UID:I = 0x2710

        .method public constructor <init>()V
            .locals 0
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public RebootOrShutdown(ZLjava/lang/String;)V
            .locals 2
            :try_start_0
            const-string v0, "sys.powerctl"
            invoke-static {v0, p2}, Landroid/os/SystemProperties;->set(Ljava/lang/String;Ljava/lang/String;)V
            :try_end_0
            .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
            return-void
            :catch_0
            move-exception v1
            return-void
        .end method

        .method public computeUid(I)I
            .locals 1
            const/16 v0, 0x2710
            add-int/2addr p1, v0
            return p1
        .end method
    """.trimIndent()

    private val sampleClassB = """
        .class public Lcom/android/server/UnmodifiedHelper;
        .super Ljava/lang/Object;

        .method public static ping()Ljava/lang/String;
            .locals 1
            const-string v0, "pong"
            return-object v0
        .end method
    """.trimIndent()

    private fun createMultiDexTestJar(): File {
        val dex1 = Reassembler.assembleSmaliSources(listOf(sampleClassA))
        val dex2 = Reassembler.assembleSmaliSources(listOf(sampleClassB))
        val jarFile = tempFolder.newFile("services.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\n".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(DexRewriter.writeDexPool(dex1.classes))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes2.dex"))
            zos.write(DexRewriter.writeDexPool(dex2.classes))
            zos.closeEntry()
        }
        return jarFile
    }

    @Test
    fun selectorScanAndRoundTripAndTransactionalRewrite() {
        val jarFile = createMultiDexTestJar()
        val outJarFile = File(tempFolder.root, "services-patched.jar")

        DexJar(jarFile).use { dexJar ->
            assertEquals(listOf("classes.dex", "classes2.dex"), dexJar.dexEntries)

            // 1. SelectorScan finds SampleService by method, const-string, and suffix without touching classes2.dex
            val matched = SelectorScan.scan(
                dexJar,
                listOf(
                    ClassSelector.Invokes("set", "Landroid/os/SystemProperties;"),
                    ClassSelector.ConstString("sys.powerctl"),
                    ClassSelector.DefinesMethod("RebootOrShutdown"),
                )
            )
            assertEquals(setOf("Lcom/android/server/SampleService;"), matched.keys)

            // 2. RoundTripCheck verifies zero structural drift on SampleService
            val sampleClassDef = matched.getValue("Lcom/android/server/SampleService;").classDef
            val diffs = RoundTripCheck.verifyClassRoundTrip(sampleClassDef)
            assertTrue("Expected empty round-trip diffs but got: $diffs", diffs.isEmpty())

            // 3. SmaliTree transaction rollback vs commit
            val tree = SmaliTree.fromMatchedClasses(matched)
            val txRollback = tree.beginTx()
            txRollback.putSource(
                "Lcom/android/server/SampleService;",
                tree.getSource("Lcom/android/server/SampleService;")!!.replace("0x2710", "0x3a98")
            )
            // Do not commit txRollback -> original source remains untouched
            assertTrue(tree.getSource("Lcom/android/server/SampleService;")!!.contains("0x2710"))

            val txCommit = tree.beginTx()
            txCommit.putSource(
                "Lcom/android/server/SampleService;",
                tree.getSource("Lcom/android/server/SampleService;")!!.replace("0x2710", "0x3a98")
            )
            txCommit.addNewClass(
                "Lcom/android/server/OneOSGuards;",
                """
                .class public final Lcom/android/server/OneOSGuards;
                .super Ljava/lang/Object;
                .method public static isGuest()Z
                    .locals 1
                    const/4 v0, 0x1
                    return v0
                .end method
                """.trimIndent()
            )
            txCommit.commit()

            val modifiedDexes = DexRewriter.rewriteModifiedDexes(dexJar, tree)
            assertEquals(setOf("classes.dex"), modifiedDexes.keys)
            JarWriter.writeUpdatedJar(jarFile, modifiedDexes, outJarFile)
        }

        // Verify output JAR: STORED, 4-byte aligned, passes DexChecks, and preserves unmodified classes2.dex
        ZipFile.builder().setFile(outJarFile).get().use { zip ->
            val dex1Entry = zip.getEntry("classes.dex")
            assertNotNull(dex1Entry)
            assertEquals(ZipEntry.STORED, dex1Entry.method)
            assertEquals(0L, dex1Entry.dataOffset % 4L)
        }

        DexJar(outJarFile).use { patchedJar ->
            val violations = DexChecks.verifyJar(patchedJar)
            assertTrue("Expected zero DexViolations, got $violations", violations.isEmpty())
            val foundGuards = SelectorScan.scan(
                patchedJar,
                listOf(ClassSelector.ExactClass("Lcom/android/server/OneOSGuards;"))
            )
            assertEquals(1, foundGuards.size)
        }
    }

    @Test
    fun orphanMoveResultCheckCatchesBrokenPatch() {
        val brokenSmali = """
            .class public Lcom/android/server/BrokenPatch;
            .super Ljava/lang/Object;

            .method public static bad()Ljava/lang/String;
                .locals 2
                nop
                move-result-object v0
                return-object v0
            .end method
        """.trimIndent()

        val dex = Reassembler.assembleSmaliSources(listOf(brokenSmali))
        val violations = OrphanMoveResultCheck.verify("classes.dex", dex)
        assertFalse("OrphanMoveResultCheck must catch move-result-object after nop", violations.isEmpty())
        assertEquals("OrphanMoveResultCheck", violations.first().checkName)
    }
}
