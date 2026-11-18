package com.fahadali.oneos.core.patch

import com.fahadali.oneos.core.dex.DexChecks
import com.fahadali.oneos.core.dex.DexJar
import com.fahadali.oneos.core.dex.DexRewriter
import com.fahadali.oneos.core.dex.Reassembler
import com.fahadali.oneos.core.model.InstalldMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PatchEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleSmaliClasses = listOf(
        // 1. IInstalld$Stub
        """
        .class public abstract Landroid/os/IInstalld${'$'}Stub;
        .super Landroid/os/Binder;
        .field static final TRANSACTION_createUserData:I = 0x1
        .field static final TRANSACTION_destroyUserData:I = 0x2
        .field static final TRANSACTION_createAppData:I = 0x3
        """.trimIndent(),
        // 2. IInstalld$Stub$Proxy (where createAppData is 0x3 in guest but 0x4 in host)
        """
        .class public Landroid/os/IInstalld${'$'}Stub${'$'}Proxy;
        .super Ljava/lang/Object;
        .field private mRemote:Landroid/os/IBinder;
        .method public constructor <init>(Landroid/os/IBinder;)V
            .locals 0
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iput-object p1, p0, Landroid/os/IInstalld${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
            return-void
        .end method
        .method public createUserData(Ljava/lang/String;III)V
            .locals 5
            const/4 v0, 0x0
            iget-object v1, p0, Landroid/os/IInstalld${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
            const/4 v2, 0x1
            const/4 v3, 0x0
            invoke-interface {v1, v2, v0, v0, v3}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
            return-void
        .end method
        .method public destroyUserData(Ljava/lang/String;II)V
            .locals 5
            const/4 v0, 0x0
            iget-object v1, p0, Landroid/os/IInstalld${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
            const/4 v2, 0x2
            const/4 v3, 0x0
            invoke-interface {v1, v2, v0, v0, v3}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
            return-void
        .end method
        .method public createAppData(Landroid/os/CreateAppDataArgs;)Landroid/os/CreateAppDataResult;
            .locals 5
            const/4 v0, 0x0
            iget-object v1, p0, Landroid/os/IInstalld${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
            const/4 v2, 0x3
            const/4 v3, 0x0
            invoke-interface {v1, v2, v0, v0, v3}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
            return-object v0
        .end method
        """.trimIndent(),
        // 3. Installer (calls IInstalld$Stub.asInterface and IInstalld.destroyUserData)
        """
        .class public Lcom/android/server/pm/Installer;
        .super Ljava/lang/Object;
        .method public connect(Landroid/os/IBinder;)Landroid/os/IInstalld;
            .locals 1
            invoke-static {p1}, Landroid/os/IInstalld${'$'}Stub;->asInterface(Landroid/os/IBinder;)Landroid/os/IInstalld;
            move-result-object v0
            return-object v0
        .end method
        .method public destroyUserData(Landroid/os/IInstalld;Ljava/lang/String;II)V
            .locals 0
            invoke-interface {p1, p2, p3, p4}, Landroid/os/IInstalld;->destroyUserData(Ljava/lang/String;II)V
            return-void
        .end method
        """.trimIndent(),
        // 4. UserDataPreparer & StorageManagerService & StorageStatsService
        """
        .class public Lcom/android/server/pm/UserDataPreparer;
        .super Ljava/lang/Object;
        .method public destroyUserDataLI(Ljava/lang/String;II)V
            .locals 1
            return-void
        .end method
        """.trimIndent(),
        """
        .class public Lcom/android/server/storage/StorageManagerService;
        .super Ljava/lang/Object;
        .method public isCeStorageUnlocked(I)Z
            .locals 1
            const/4 v0, 0x0
            return v0
        .end method
        .method public lockKeys(Landroid/os/IVold;I)V
            .locals 0
            invoke-interface {p1, p2}, Landroid/os/IVold;->lockUserKey(I)V
            invoke-interface {p1, p2}, Landroid/os/IVold;->destroyUserKey(I)V
            return-void
        .end method
        """.trimIndent(),
        """
        .class public Lcom/android/server/usage/StorageStatsService;
        .super Ljava/lang/Object;
        .method public isQuotaSupported(Ljava/lang/String;)Z
            .locals 1
            const/4 v0, 0x1
            return v0
        .end method
        """.trimIndent(),
        // 5. PowerManagerService & ShutdownThread & Watchdog
        """
        .class public Lcom/android/server/power/PowerManagerService;
        .super Ljava/lang/Object;
        .method public static lowLevelReboot(Ljava/lang/String;)V
            .locals 1
            const-string v0, "sys.powerctl"
            invoke-static {v0, p0}, Landroid/os/SystemProperties;->set(Ljava/lang/String;Ljava/lang/String;)V
            return-void
        .end method
        """.trimIndent(),
        """
        .class public Lcom/android/server/power/ShutdownThread;
        .super Ljava/lang/Object;
        .method public static reboot(Landroid/content/Context;Ljava/lang/String;Z)V
            .locals 2
            return-void
        .end method
        """.trimIndent(),
        """
        .class public Lcom/android/server/Watchdog;
        .super Ljava/lang/Object;
        .method private doSysRq(C)V
            .locals 1
            return-void
        .end method
        """.trimIndent(),
        // 6. AppIdSettingMap & PackageSetting
        """
        .class public Lcom/android/server/pm/AppIdSettingMap;
        .super Ljava/lang/Object;
        .field private mFirstAvailableAppId:I
        .field private final mNonSystemSettings:Lcom/android/server/utils/WatchedArrayList;
        .method public registerExistingAppId(ILcom/android/server/pm/SettingBase;Ljava/lang/Object;)Z
            .locals 1
            const/4 v0, 0x1
            return v0
        .end method
        .method public acquireAndRegisterNewAppId(Lcom/android/server/pm/SettingBase;)I
            .locals 3
            iget v0, p0, Lcom/android/server/pm/AppIdSettingMap;->mFirstAvailableAppId:I
            return v0
        .end method
        """.trimIndent(),
        """
        .class public Lcom/android/server/pm/PackageSetting;
        .super Lcom/android/server/pm/SettingBase;
        .method public getPackageName()Ljava/lang/String;
            .locals 1
            const-string v0, "pkg"
            return-object v0
        .end method
        """.trimIndent(),
    )

    private fun buildFixtureJar(): File {
        val dex = Reassembler.assembleSmaliSources(sampleSmaliClasses)
        val jarFile = tempFolder.newFile("services.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(DexRewriter.writeDexPool(dex.classes))
            zos.closeEntry()
        }
        return jarFile
    }

    @Test
    fun patchesServicesJarTransactionallyAndVerifiesBytecode() {
        val srcJar = buildFixtureJar()
        val outJar = File(tempFolder.root, "services_patched.jar")
        val hostTable = mapOf(
            "createUserData" to InstalldMethod("createUserData", 1, "(Ljava/lang/String;III)V"),
            "destroyUserData" to InstalldMethod("destroyUserData", 2, "(Ljava/lang/String;II)V"),
            "createAppData" to InstalldMethod("createAppData", 4, "(Landroid/os/CreateAppDataArgs;)Landroid/os/CreateAppDataResult;"),
        )
        val ctx = PatchContext(hostInstalldMethods = hostTable, guestSdk = 36)

        val specs = listOf(
            PlannedPatchSpec("safety.vold_key_guard", "VoldKeysPatch", 1, true),
            PlannedPatchSpec("safety.storage_guards", "StorageGuardsPatch", 3, true, mapOf("jar" to "services.jar")),
            PlannedPatchSpec("safety.power_guards", "PowerGuardsPatch", 2, true),
            PlannedPatchSpec("safety.no_sysrq_crash", "SysRqPatch", 1, true, mapOf("jar" to "services.jar")),
            PlannedPatchSpec("safety.installd_abi_proxy", "InstalldAbiPatch", 1, true, mapOf("mode" to "proxy")),
            PlannedPatchSpec("safety.app_id_offset", "AppIdOffsetPatch", 1, true),
            PlannedPatchSpec("safety.ksu_manager_appid", "KsuManagerAppIdPatch", 1, true),
        )

        val result = PatchEngine.patchJar(srcJar, outJar, specs, ctx)
        assertTrue(outJar.exists())
        assertEquals(7, result.patchReports.count { it.applied })

        DexJar(outJar).use { patched ->
            assertTrue(DexChecks.verifyJar(patched).isEmpty())
            val allClasses = patched.getDexFile("classes.dex").classes.associateBy { it.type }
            assertNotNull("OneOSInstalldProxy must be added", allClasses["Lcom/android/server/pm/OneOSInstalldProxy;"])
        }
    }

    @Test
    fun binaryPatcherRejectsUnpinnedAndVerifiesElf64() {
        // Create synthetic ELF64 AArch64 header
        val elf = ByteArray(1024)
        elf[0] = 0x7F.toByte()
        elf[1] = 'E'.code.toByte()
        elf[2] = 'L'.code.toByte()
        elf[3] = 'F'.code.toByte()
        elf[4] = 2 // 64-bit
        elf[5] = 1 // little-endian
        ByteBuffer.wrap(elf, 18, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(183) // EM_AARCH64

        val voldIn = tempFolder.newFile("vold").apply { writeBytes(elf) }
        val incIn = tempFolder.newFile("service.incremental.so").apply { writeBytes(elf) }
        val apexIn = tempFolder.newFile("crashrecovery.apex").apply { writeBytes(ByteArray(128)) }
        val voldOut = File(tempFolder.root, "vold_patched")
        val incOut = File(tempFolder.root, "inc_patched.so")

        val outcome = BinaryPatcher.patchVoldAndIncrementalIfPinned(voldIn, incIn, apexIn, voldOut, incOut)
        assertFalse(outcome.patched)
        assertTrue(outcome.requiresRebootReturnMode)
        assertFalse(voldOut.exists())
    }

    @Test
    fun apexPayloadExtractorParsesProtobufManifestAndExtractsPayload() {
        // Construct a synthetic apex_manifest.pb: field 1 = "com.android.tethering", field 2 = 360000000, field 8 = "libnativehelper.so"
        val pb = ByteArrayOutputStream().apply {
            val nameBytes = "com.android.tethering".toByteArray()
            write((1 shl 3) or 2)
            write(nameBytes.size)
            write(nameBytes)

            write((2 shl 3) or 0)
            write(42) // version = 42

            val reqBytes = "libnativehelper.so".toByteArray()
            write((8 shl 3) or 2)
            write(reqBytes.size)
            write(reqBytes)
        }.toByteArray()

        val parsed = ApexPayloadExtractor.parseApexManifestPb(pb)
        assertEquals("com.android.tethering", parsed.name)
        assertEquals(42L, parsed.version)
        assertEquals(listOf("libnativehelper.so"), parsed.requireNativeLibs)
    }
}
