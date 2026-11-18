package com.fahadali.oneos.core.safety

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.fahadali.oneos.core.model.InstalldDerivation
import com.fahadali.oneos.core.model.InstalldMethod
import java.io.File
import java.util.zip.ZipFile

/**
 * One UI's installd interface from its own framework dex files: the port of
 * `scripts/host-installd-table.sh` described in docs/app/DESIGN.md section 6.4.
 * Read-only (dexlib2 parses bytes; nothing is written).
 *
 * - Codes: the `static final int TRANSACTION_<name>` values of
 *   `android.os.IInstalld$Stub`.
 * - Signatures: `(<params>)<return>` of the same-named method of
 *   `IInstalld$Stub$Proxy`. A name that appears twice is refused (AIDL has no
 *   overloads, so it would not be the AIDL class); names the proxy lacks are
 *   left out, like the script's `-`.
 * - Which class counts: the first input in class-loader order that defines
 *   it. A later definition with a different table makes the result ambiguous
 *   (refused); an identical one is only noted.
 */
object HostInstalldTable {
    const val STUB = "Landroid/os/IInstalld\$Stub;"
    const val PROXY = "Landroid/os/IInstalld\$Stub\$Proxy;"
    private const val PREFIX = "TRANSACTION_"
    private val DEX_ENTRY = Regex("""^classes(\d*)\.dex$""")

    /**
     * One captured input, in class-loader order: a dex extracted from [jarPath]
     * ([entry] = `classes.dex`), or the whole jar ([entry] null).
     */
    data class Input(val jarPath: String, val entry: String?, val file: File, val capturePath: String)

    /** What one dex holds; null from [read] when the class is not in it. */
    data class DexTable(val methods: List<InstalldMethod>, val problems: List<String>)

    fun read(dexBytes: ByteArray): DexTable? {
        val dex = DexBackedDexFile(Opcodes.getDefault(), dexBytes)
        val stub = dex.classes.firstOrNull { it.type == STUB } ?: return null
        val proxy = dex.classes.firstOrNull { it.type == PROXY }
        val problems = mutableListOf<String>()

        val codes = linkedMapOf<String, Int>()
        for (f in stub.staticFields) {
            if (!f.name.startsWith(PREFIX) || f.type != "I") continue
            val v = f.initialValue as? IntEncodedValue
            if (v == null) {
                problems += "${f.name} has no constant value"
                continue
            }
            codes[f.name.removePrefix(PREFIX)] = v.value
        }
        if (codes.isEmpty()) problems += "IInstalld\$Stub has no TRANSACTION_ fields"

        val sigs = mutableMapOf<String, MutableList<String>>()
        if (proxy == null) {
            problems += "IInstalld\$Stub\$Proxy not found next to IInstalld\$Stub"
        } else {
            for (m in proxy.methods) {
                val sig = "(" + m.parameterTypes.joinToString("") + ")" + m.returnType
                sigs.getOrPut(m.name) { mutableListOf() } += sig
            }
        }
        val methods = mutableListOf<InstalldMethod>()
        for ((name, code) in codes) {
            val s = sigs[name] ?: continue
            if (s.size != 1) {
                problems += "$name appears ${s.size} times in the proxy"
                continue
            }
            methods += InstalldMethod(name, code, s.single())
        }
        return DexTable(methods.sortedBy { it.code }, problems)
    }

    /**
     * Derives the table from [inputs] (class-loader order). [jarSha256] maps
     * jar paths to the SHA-256 the probe read on the phone.
     */
    fun derive(inputs: List<Input>, jarSha256: Map<String, String>): InstalldDerivation {
        val used = inputs.map { it.capturePath }
        if (inputs.isEmpty()) return InstalldDerivation.failed("no dex file with IInstalld\$Stub was captured", used)
        data class Hit(val jar: String, val entry: String, val table: DexTable)
        val hits = mutableListOf<Hit>()
        val notes = mutableListOf<String>()
        try {
            for (input in inputs) {
                for ((entry, bytes) in dexes(input)) {
                    val t = read(bytes) ?: continue
                    hits += Hit(input.jarPath, entry, t)
                }
            }
        } catch (e: Exception) {
            return InstalldDerivation.failed("could not read the dex files: ${e.javaClass.simpleName}: ${e.message}", used)
        } catch (e: OutOfMemoryError) {
            return InstalldDerivation.failed("not enough memory to read the dex files", used)
        }
        val first = hits.firstOrNull()
            ?: return InstalldDerivation.failed("IInstalld\$Stub is in none of the captured dex files", used)
        for (other in hits.drop(1)) {
            if (other.table.methods != first.table.methods) {
                return InstalldDerivation.failed(
                    "ambiguous: ${other.jar}!${other.entry} defines a different IInstalld than ${first.jar}!${first.entry}",
                    used,
                )
            }
            notes += "${other.jar}!${other.entry} defines the same IInstalld again"
        }
        if (first.table.problems.isNotEmpty()) {
            return InstalldDerivation.failed(first.table.problems.joinToString("; "), used)
        }
        if (first.table.methods.isEmpty()) return InstalldDerivation.failed("IInstalld has no methods", used)
        return InstalldDerivation(
            methods = first.table.methods,
            sourceJar = first.jar,
            sourceEntry = first.entry,
            sourceSha256 = jarSha256[first.jar],
            inputs = used,
            error = null,
            notes = notes,
        )
    }

    /**
     * Inputs from the `installd.dex` probe's files (source `jar!entry` or a
     * whole `jar`), ordered like [classPath] (boot class path, then system
     * server class path); jars not on it go last, in capture order.
     */
    fun inputsFrom(captureDir: File, files: List<com.fahadali.oneos.core.model.CapturedFile>, classPath: List<String>): List<Input> {
        val inputs = files.map { f ->
            val bang = f.source.indexOf('!')
            if (bang >= 0) {
                Input(f.source.substring(0, bang), f.source.substring(bang + 1), File(captureDir, f.path), f.path)
            } else {
                Input(f.source, null, File(captureDir, f.path), f.path)
            }
        }
        return inputs.withIndex()
            .sortedWith(compareBy({ classPath.indexOf(it.value.jarPath).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }
    }

    /** The dex files of one input: the file itself, or the `classes*.dex` entries of a whole jar in order. */
    private fun dexes(input: Input): Sequence<Pair<String, ByteArray>> = sequence {
        if (input.entry != null) {
            yield(input.entry to input.file.readBytes())
            return@sequence
        }
        ZipFile(input.file).use { zip ->
            val entries = zip.entries().toList()
                .filter { DEX_ENTRY.matches(it.name) }
                .sortedBy { DEX_ENTRY.matchEntire(it.name)!!.groupValues[1].ifEmpty { "1" }.toInt() }
            for (e in entries) yield(e.name to zip.getInputStream(e).use { it.readBytes() })
        }
    }

    const val EXPECTED_METHODS_A16 = 78
    const val EXPECTED_METHODS_A17 = 85

    data class ParcelableField(val name: String, val type: String)

    data class ParcelableSpec(
        val classDescriptor: String,
        val expectedFields: List<ParcelableField>,
    )

    val EXPECTED_PARCELABLES: List<ParcelableSpec> = listOf(
        ParcelableSpec(
            classDescriptor = "Landroid/os/CreateAppDataArgs;",
            expectedFields = listOf(
                ParcelableField("uuid", "Ljava/lang/String;"),
                ParcelableField("packageName", "Ljava/lang/String;"),
                ParcelableField("userId", "I"),
                ParcelableField("flags", "I"),
                ParcelableField("appId", "I"),
                ParcelableField("seInfo", "Ljava/lang/String;"),
                ParcelableField("targetSdkVersion", "I"),
            ),
        ),
        ParcelableSpec(
            classDescriptor = "Landroid/os/CreateAppDataResult;",
            expectedFields = listOf(
                ParcelableField("ceDataInode", "J"),
                ParcelableField("exceptionCode", "I"),
            ),
        ),
        ParcelableSpec(
            classDescriptor = "Landroid/os/ReconcileSdkDataArgs;",
            expectedFields = listOf(
                ParcelableField("uuid", "Ljava/lang/String;"),
                ParcelableField("packageName", "Ljava/lang/String;"),
                ParcelableField("subDirNames", "Ljava/util/List;"),
                ParcelableField("userId", "I"),
                ParcelableField("appId", "I"),
                ParcelableField("previousAppId", "I"),
                ParcelableField("seInfo", "Ljava/lang/String;"),
                ParcelableField("flags", "I"),
            ),
        ),
    )

    /**
     * Verifies that AIDL Parcelables (`CreateAppDataArgs`, `CreateAppDataResult`,
     * `ReconcileSdkDataArgs`) present in [dexBytes] contain all expected wire fields
     * with matching types. Returns a list of mismatch descriptions (empty if valid).
     */
    fun verifyParcelables(dexBytes: ByteArray): List<String> {
        val dex = DexBackedDexFile(Opcodes.getDefault(), dexBytes)
        val classMap = dex.classes.associateBy { it.type }
        val problems = mutableListOf<String>()
        for (spec in EXPECTED_PARCELABLES) {
            val cls = classMap[spec.classDescriptor] ?: continue
            val instanceFields = cls.instanceFields.map { ParcelableField(it.name, it.type) }
            for (expected in spec.expectedFields) {
                val actual = instanceFields.firstOrNull { it.name == expected.name }
                if (actual == null) {
                    problems += "${spec.classDescriptor}: missing field ${expected.name}:${expected.type}"
                } else if (actual.type != expected.type) {
                    problems += "${spec.classDescriptor}.${expected.name}: expected ${expected.type}, found ${actual.type}"
                }
            }
        }
        return problems
    }

    /**
     * Compares AIDL Parcelable instance field layouts in order between [hostDexBytes]
     * and [guestDexBytes] so wire serialization mismatches fail closed before boot.
     */
    fun verifyGuestAgainstHostParcelables(hostDexBytes: ByteArray, guestDexBytes: ByteArray): List<String> {
        val hostDex = DexBackedDexFile(Opcodes.getDefault(), hostDexBytes)
        val guestDex = DexBackedDexFile(Opcodes.getDefault(), guestDexBytes)
        val hostClasses = hostDex.classes.associateBy { it.type }
        val guestClasses = guestDex.classes.associateBy { it.type }
        val problems = mutableListOf<String>()
        for (spec in EXPECTED_PARCELABLES) {
            val hCls = hostClasses[spec.classDescriptor] ?: continue
            val gCls = guestClasses[spec.classDescriptor] ?: continue
            val hFields = hCls.instanceFields.map { "${it.name}:${it.type}" }
            val gFields = gCls.instanceFields.map { "${it.name}:${it.type}" }
            if (hFields != gFields) {
                problems += "${spec.classDescriptor} parcelable field layout mismatch: host=$hFields vs guest=$gFields"
            }
        }
        return problems
    }

    /**
     * Validates that the derived installd method table matches the expected method count
     * and core entrypoints for Android 16 (SDK 36, 78 methods) or Android 17 (SDK 37, 85 methods).
     */
    fun validateMethodCountForSdk(sdk: Int, methods: List<InstalldMethod>): List<String> {
        val problems = mutableListOf<String>()
        val expectedCount = when (sdk) {
            36 -> EXPECTED_METHODS_A16
            37 -> EXPECTED_METHODS_A17
            else -> {
                problems += "Unsupported SDK $sdk for installd table validation (expected 36 or 37)"
                return problems
            }
        }
        if (methods.size != expectedCount) {
            problems += "SDK $sdk expects $expectedCount installd methods, found ${methods.size}"
        }
        val byName = methods.associateBy { it.name }
        for (required in listOf("createUserData", "createAppData", "destroyAppData", "dexopt", "rmPackageDir")) {
            if (required !in byName) {
                problems += "SDK $sdk installd table missing core method '$required'"
            }
        }
        return problems
    }
}
