package com.fahadali.oneos.core.safety

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.fahadali.oneos.core.model.InstalldTableFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafetyVerificationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildDex(vararg classes: ClassDef): ByteArray {
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), classes.toList()))
        return store.data
    }

    private fun buildJar(name: String, dexBytes: ByteArray): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex"))
            z.write(dexBytes)
            z.closeEntry()
        }
        return f
    }

    @Test
    fun guardVerifierCatchesDirectForbiddenCallsAndAllowsGuardedExceptions() {
        // 1. Clean DEX: StorageManagerService.<init> calling IVold.reset, and OneOSInstalldProxy calling IInstalld$Stub.asInterface
        val cleanStorageInit = ImmutableMethod(
            "Lcom/android/server/StorageManagerService;",
            "<init>",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_INTERFACE,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference("Landroid/os/IVold;", "reset", emptyList(), "V"),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )
        val cleanInstalldProxy = ImmutableMethod(
            "Lcom/android/server/pm/OneOSInstalldProxy;",
            "connect",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference(
                            "Landroid/os/IInstalld\$Stub;",
                            "asInterface",
                            listOf("Landroid/os/IBinder;"),
                            "Landroid/os/IInstalld;",
                        ),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )
        val cleanDex = buildDex(
            ImmutableClassDef("Lcom/android/server/StorageManagerService;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(cleanStorageInit)),
            ImmutableClassDef("Lcom/android/server/pm/OneOSInstalldProxy;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(cleanInstalldProxy)),
        )
        val cleanResult = GuardVerifier.verifyDexBytes("services.jar", "classes.dex", cleanDex)
        assertTrue("Expected clean DEX to pass: ${cleanResult.violations}", cleanResult.passed)

        // 2. Dirty DEX: direct IVold.destroyUserStorage, IWeaver.write, and unproxied IInstalld$Stub.asInterface
        val dirtyMethod = ImmutableMethod(
            "Lcom/android/server/oem/OemStorageHelper;",
            "wipeHostStorage",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_INTERFACE,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference("Landroid/os/IVold;", "destroyUserStorage", emptyList(), "V"),
                    ),
                    ImmutableInstruction35c(
                        Opcode.INVOKE_INTERFACE,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference("Landroid/hardware/weaver/IWeaver;", "write", emptyList(), "V"),
                    ),
                    ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference(
                            "Landroid/os/IInstalld\$Stub;",
                            "asInterface",
                            listOf("Landroid/os/IBinder;"),
                            "Landroid/os/IInstalld;",
                        ),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )
        val dirtyDex = buildDex(
            ImmutableClassDef("Lcom/android/server/oem/OemStorageHelper;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(dirtyMethod)),
        )
        val dirtyResult = GuardVerifier.verifyDexBytes("oplus-services.jar", "classes.dex", dirtyDex)
        assertFalse(dirtyResult.passed)
        val categories = dirtyResult.violations.map { it.category }.toSet()
        assertTrue("DIRECT_VOLD_CALL" in categories)
        assertTrue("DIRECT_WEAVER_CALL" in categories)
        assertTrue("UNPROXIED_INSTALLD_BINDER" in categories)
    }

    @Test
    fun guardVerifierCatchesAdversarialReflectionMethodHandlesRawTransactAndDynamicCodeLoading() {
        // 1. Reflection bypass: Class.forName("android.os.IVold") + getDeclaredMethod("unlockUserKey")
        val reflectionMethod = ImmutableMethod(
            "Lcom/android/server/adversarial/ReflectiveCaller;",
            "bypassVold",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("android.os.IVold")),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("unlockUserKey")),
                    ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference("Ljava/lang/Class;", "forName", listOf("Ljava/lang/String;"), "Ljava/lang/Class;"),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )

        // 2. MethodHandles.Lookup bypass
        val mhMethod = ImmutableMethod(
            "Lcom/android/server/adversarial/MethodHandleCaller;",
            "bypassInstalld",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("destroyUserData")),
                    ImmutableInstruction35c(
                        Opcode.INVOKE_VIRTUAL,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference(
                            "Ljava/lang/invoke/MethodHandles\$Lookup;",
                            "findVirtual",
                            emptyList(),
                            "Ljava/lang/invoke/MethodHandle;",
                        ),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )

        // 3. Raw IBinder.transact on android.os.IVold
        val rawTransactMethod = ImmutableMethod(
            "Lcom/android/server/adversarial/RawBinderCaller;",
            "rawTransact",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("android.os.IVold")),
                    ImmutableInstruction35c(
                        Opcode.INVOKE_INTERFACE,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference("Landroid/os/IBinder;", "transact", emptyList(), "Z"),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )

        // 4. Dynamic code loading in system_server via DexClassLoader
        val dclMethod = ImmutableMethod(
            "Lcom/android/server/adversarial/DynamicLoader;",
            "loadExternalDex",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_DIRECT,
                        1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference("Ldalvik/system/DexClassLoader;", "<init>", emptyList(), "V"),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null,
                null,
            ),
        )

        val adversarialDex = buildDex(
            ImmutableClassDef("Lcom/android/server/adversarial/ReflectiveCaller;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(reflectionMethod)),
            ImmutableClassDef("Lcom/android/server/adversarial/MethodHandleCaller;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(mhMethod)),
            ImmutableClassDef("Lcom/android/server/adversarial/RawBinderCaller;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(rawTransactMethod)),
            ImmutableClassDef("Lcom/android/server/adversarial/DynamicLoader;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(dclMethod)),
        )

        val result = GuardVerifier.verifyDexBytes("services.jar", "classes.dex", adversarialDex)
        assertFalse(result.passed)
        val categories = result.violations.map { it.category }.toSet()
        assertTrue("REFLECTION_BYPASS" in categories)
        assertTrue("METHOD_HANDLE_BYPASS" in categories)
        assertTrue("RAW_BINDER_TRANSACT" in categories)
        assertTrue("DYNAMIC_CODE_LOADING" in categories)
    }

    @Test
    fun guardVerifierCatchesBootClassPathShadowing() {
        val shadowedClass = ImmutableClassDef(
            "Landroid/security/keystore2/AndroidKeyStoreMaintenance;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            null, null, null, null, null,
        )
        val earlierJar = buildJar("core-oj.jar", buildDex(shadowedClass))
        val frameworkPatchedJar = buildJar("framework.jar", buildDex(shadowedClass))

        val violations = GuardVerifier.verifyNoBootClassPathShadowing(
            patchedFrameworkJar = frameworkPatchedJar,
            bootClassPathJars = listOf(earlierJar, frameworkPatchedJar),
            patchedClassDescriptors = setOf("Landroid/security/keystore2/AndroidKeyStoreMaintenance;"),
        )
        assertEquals(1, violations.size)
        assertEquals("BOOTCLASSPATH_SHADOW", violations.single().category)
    }

    @Test
    fun guestImageInspectorValidatesElf64BtiPacAndRefusesSmeOnSm8450() {
        // Construct a synthetic ELF64 AArch64 binary with a .note.gnu.property section (BTI|PAC)
        // and an executable .text section.
        fun makeSyntheticElf(instructions: IntArray, btiPacFlags: Int = 0x3): ByteArray {
            val codeBytes = ByteBuffer.allocate(instructions.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (insn in instructions) putInt(insn)
            }.array()
            // GNU property note for AArch64 FEATURE_1_AND (BTI=1, PAC=2)
            val noteBytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(4) // namesz = 4 ("GNU\0")
                putInt(16) // descsz = 16
                putInt(GuestImageInspector.NT_GNU_PROPERTY_TYPE_0)
                put('G'.code.toByte())
                put('N'.code.toByte())
                put('U'.code.toByte())
                put(0.toByte())
                putInt(GuestImageInspector.GNU_PROPERTY_AARCH64_FEATURE_1_AND)
                putInt(4) // pr_datasz = 4
                putInt(btiPacFlags)
                putInt(0) // 8-byte alignment padding
            }.array()

            val headerSize = 64
            val noteOffset = headerSize
            val codeOffset = noteOffset + noteBytes.size
            val shoff = codeOffset + codeBytes.size
            val totalSize = shoff + 3 * 64 // null section + .note.gnu.property + .text

            val elf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
            // e_ident
            elf.put(0, 0x7f.toByte())
            elf.put(1, 'E'.code.toByte())
            elf.put(2, 'L'.code.toByte())
            elf.put(3, 'F'.code.toByte())
            elf.put(4, GuestImageInspector.ELFCLASS64.toByte())
            elf.put(5, GuestImageInspector.ELFDATA2LSB.toByte())
            elf.put(6, 1.toByte()) // EV_CURRENT
            elf.putShort(16, 3) // ET_DYN
            elf.putShort(18, GuestImageInspector.EM_AARCH64.toShort())
            elf.putInt(20, 1)
            elf.putLong(40, shoff.toLong()) // e_shoff
            elf.putShort(52, 64) // e_ehsize
            elf.putShort(58, 64) // e_shentsize
            elf.putShort(60, 3) // e_shnum

            // Write note & code payloads
            System.arraycopy(noteBytes, 0, elf.array(), noteOffset, noteBytes.size)
            System.arraycopy(codeBytes, 0, elf.array(), codeOffset, codeBytes.size)

            // Section 1: SHT_NOTE
            val sh1 = shoff + 64
            elf.putInt(sh1 + 4, GuestImageInspector.SHT_NOTE)
            elf.putLong(sh1 + 24, noteOffset.toLong())
            elf.putLong(sh1 + 32, noteBytes.size.toLong())

            // Section 2: SHT_PROGBITS | SHF_EXECINSTR
            val sh2 = shoff + 128
            elf.putInt(sh2 + 4, GuestImageInspector.SHT_PROGBITS)
            elf.putLong(sh2 + 8, GuestImageInspector.SHF_EXECINSTR)
            elf.putLong(sh2 + 24, codeOffset.toLong())
            elf.putLong(sh2 + 32, codeBytes.size.toLong())

            return elf.array()
        }

        // 1. Binary with SVE RDVL (0x04bf5000) and BF16 BFDOT (0x6e40fc00) -> compatible with SM8450
        val compatibleElf = makeSyntheticElf(intArrayOf(0x04bf5000, 0x6e40fc00, 0xd65f03c0.toInt()))
        val okInspect = GuestImageInspector.inspectElf("surfaceflinger", compatibleElf, GuestImageInspector.SM8450_CPU_FEATURES)
        assertTrue(okInspect.validElf64Aarch64)
        assertTrue(okInspect.btiEnabled)
        assertTrue(okInspect.pacEnabled)
        assertTrue(GuestImageInspector.IsaExtension.SVE in okInspect.detectedExtensions)
        assertTrue(GuestImageInspector.IsaExtension.BF16 in okInspect.detectedExtensions)
        assertTrue(okInspect.unsupportedOnHost.isEmpty())

        // 2. Binary with SME smstart (0xd503477f) -> refused on SM8450 (no "sme" in SM8450_CPU_FEATURES)
        val smeElf = makeSyntheticElf(intArrayOf(0xd503477f.toInt(), 0xd65f03c0.toInt()))
        val smeInspect = GuestImageInspector.inspectElf("surfaceflinger", smeElf, GuestImageInspector.SM8450_CPU_FEATURES)
        assertTrue(smeInspect.validElf64Aarch64)
        assertTrue(GuestImageInspector.IsaExtension.SME in smeInspect.unsupportedOnHost)
        assertTrue(smeInspect.error!!.contains("SME"))
    }

    @Test
    fun uidIsolationValidatorVerifiesAllBandsAndPackagesMigration() {
        assertEquals(UidIsolationValidator.UidDomain.SYSTEM_OR_OEM, UidIsolationValidator.classifyUid(1000))
        assertEquals(UidIsolationValidator.UidDomain.HOST_APP, UidIsolationValidator.classifyUid(10123))
        assertEquals(UidIsolationValidator.UidDomain.GUEST_APP, UidIsolationValidator.classifyUid(15123))
        assertEquals(UidIsolationValidator.UidDomain.HOST_SDK_SANDBOX, UidIsolationValidator.classifyUid(20123))
        assertEquals(UidIsolationValidator.UidDomain.GUEST_SDK_SANDBOX, UidIsolationValidator.classifyUid(25123))
        assertEquals(UidIsolationValidator.UidDomain.ISOLATED, UidIsolationValidator.classifyUid(90005))
        assertEquals(UidIsolationValidator.UidDomain.APP_ZYGOTE_ISOLATED, UidIsolationValidator.classifyUid(99005))

        // Multi-user UID (User 10, Guest App 15042 -> 1015042 -> SDK Sandbox 1025042)
        val user10GuestUid = UidIsolationValidator.getUid(10, 15042)
        assertEquals(1015042, user10GuestUid)
        assertEquals(UidIsolationValidator.UidDomain.GUEST_APP, UidIsolationValidator.classifyUid(user10GuestUid))
        val user10SandboxUid = UidIsolationValidator.toSdkSandboxUid(user10GuestUid)
        assertEquals(1025042, user10SandboxUid)
        assertEquals(UidIsolationValidator.UidDomain.GUEST_SDK_SANDBOX, UidIsolationValidator.classifyUid(user10SandboxUid))
        assertEquals(user10GuestUid, UidIsolationValidator.getAppUidFromSdkSandboxUid(user10SandboxUid))

        // KernelSU manager App ID must be in 10000..14999
        assertTrue(UidIsolationValidator.validateKernelSuManagerAppId(10155).isEmpty())
        assertFalse(UidIsolationValidator.validateKernelSuManagerAppId(15155).isEmpty())

        // Unmigrated host-band UID in guest packages.xml is rejected when allowMigrationFromAospBand=false
        val entries = listOf(
            UidIsolationValidator.PackageUidEntry("com.android.systemui", 10045, isSharedUser = false),
            UidIsolationValidator.PackageUidEntry("android.uid.shared", 10046, isSharedUser = true),
        )
        val listText = "com.android.systemui 10045 0 /data/user/0/com.android.systemui default:targetSdkVersion=36 none\n"
        val rejected = UidIsolationValidator.validateAndMigratePackages(entries, listText, allowMigrationFromAospBand = false)
        assertFalse(rejected.valid)

        // Migrated +5000 into 15045 / 15046 when allowMigrationFromAospBand=true
        val migrated = UidIsolationValidator.validateAndMigratePackages(entries, listText, allowMigrationFromAospBand = true)
        assertTrue(migrated.valid)
        assertEquals(15045, migrated.migratedEntries[0].appId)
        assertEquals(15046, migrated.migratedEntries[1].appId)
    }

    @Test
    fun hostInstalldTableVerifiesParcelablesAndA16A17Tables() {
        val a16File = File("../../../switcher/patches/host_installd_a16.txt")
        val a17File = File("../../../switcher/patches/host_installd.txt")
        if (a16File.isFile) {
            val a16Methods = InstalldTableFormat.parseLegacy(a16File.readText())
            assertEquals(emptyList<String>(), HostInstalldTable.validateMethodCountForSdk(36, a16Methods))
        }
        if (a17File.isFile) {
            val a17Methods = InstalldTableFormat.parseLegacy(a17File.readText())
            assertEquals(emptyList<String>(), HostInstalldTable.validateMethodCountForSdk(37, a17Methods))
        }

        // Verify Parcelable field check detects missing/mismatched fields
        val validCreateAppDataResult = ImmutableClassDef(
            "Landroid/os/CreateAppDataResult;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            listOf("Landroid/os/Parcelable;"),
            null,
            null,
            null,
            listOf(
                ImmutableField("Landroid/os/CreateAppDataResult;", "ceDataInode", "J", AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField("Landroid/os/CreateAppDataResult;", "exceptionCode", "I", AccessFlags.PUBLIC.value, null, null, null),
            ),
            null,
            null,
        )
        val brokenCreateAppDataResult = ImmutableClassDef(
            "Landroid/os/CreateAppDataResult;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            listOf("Landroid/os/Parcelable;"),
            null,
            null,
            null,
            listOf(
                ImmutableField("Landroid/os/CreateAppDataResult;", "ceDataInode", "I", AccessFlags.PUBLIC.value, null, null, null), // wrong type I instead of J
            ),
            null,
            null,
        )
        assertEquals(emptyList<String>(), HostInstalldTable.verifyParcelables(buildDex(validCreateAppDataResult)))
        val problems = HostInstalldTable.verifyParcelables(buildDex(brokenCreateAppDataResult))
        assertEquals(2, problems.size)
        val diff = HostInstalldTable.verifyGuestAgainstHostParcelables(
            buildDex(validCreateAppDataResult),
            buildDex(brokenCreateAppDataResult),
        )
        assertEquals(1, diff.size)
    }
}
