package com.fahadali.oneos.core.patch

import com.fahadali.oneos.core.dex.ClassSelector
import com.fahadali.oneos.core.dex.SmaliTree
import com.fahadali.oneos.core.model.InstalldMethod

class PatchRefusedException(
    val handler: String,
    override val message: String,
) : Exception("$handler: $message")

enum class OnAnchorMissing {
    REFUSE_SESSION,
    BLOCK_IMAGE,
    SKIP,
    SKIP_WARN,
}

data class PatchSite(
    val classDescriptor: String,
    val member: String,
    val detail: String,
)

data class PatchReport(
    val entryId: String,
    val handler: String,
    val applied: Boolean,
    val sites: List<PatchSite> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val reason: String? = null,
)

data class PatchContext(
    val hostInstalldMethods: Map<String, InstalldMethod> = emptyMap(),
    val helperSmaliSources: Map<String, String> = emptyMap(),
    val guestSdk: Int = 36,
    val guestFamily: String = "aosp",
)

interface SmaliPatch {
    val entryId: String
    val handler: String
    val ruleVersion: Int
    val isSafety: Boolean
    val defaultOnAnchorMissing: OnAnchorMissing
        get() = if (isSafety) OnAnchorMissing.REFUSE_SESSION else OnAnchorMissing.SKIP_WARN

    fun selectors(args: Map<String, String> = emptyMap()): List<ClassSelector>

    @Throws(PatchRefusedException::class)
    fun apply(
        tx: SmaliTree.Tx,
        args: Map<String, String> = emptyMap(),
        ctx: PatchContext = PatchContext(),
    ): PatchReport
}

internal object SmaliEditUtils {
    private val METHOD_HEADER = Regex("""^\.method ((?:[a-z-]+ )*)([^\s(]+\([^)]*\)\S+)$""")
    private val LOCALS_LINE = Regex("""^(\s*)\.locals (\d+)\s*$""")
    private val MOVE_RESULT_LINE = Regex("""^\s*move-result(?:-wide|-object)? ([vp]\d+)\s*$""")

    data class MethodSpan(
        val signature: String,
        val modifiers: Set<String>,
        val startLine: Int,
        val endLine: Int,
        val localsLine: Int,
        val localsCount: Int,
        val bodyStartLine: Int,
    )

    fun findMethods(lines: List<String>, classDesc: String): Map<String, List<MethodSpan>> {
        val result = LinkedHashMap<String, MutableList<MethodSpan>>()
        var i = 0
        while (i < lines.size) {
            val m = METHOD_HEADER.matchEntire(lines[i])
            if (m == null) {
                i++
                continue
            }
            val mods = m.groupValues[1].trim().split(" ").filter { it.isNotEmpty() }.toSet()
            val sig = m.groupValues[2]
            var end = i + 1
            while (end < lines.size && lines[end].trim() != ".end method") {
                end++
            }
            if (end >= lines.size) {
                throw IllegalStateException("$classDesc: method $sig has no .end method")
            }
            var localsIdx = -1
            var localsCount = 0
            var j = i + 1
            while (j < end) {
                val trimmed = lines[j].trim()
                val lm = LOCALS_LINE.matchEntire(lines[j])
                if (lm != null) {
                    localsIdx = j
                    localsCount = lm.groupValues[2].toInt()
                    j++
                    continue
                }
                if (trimmed.startsWith(".annotation")) {
                    while (j < end && lines[j].trim() != ".end annotation") j++
                    j++
                    continue
                }
                if (trimmed.isEmpty() || trimmed.startsWith(".param") || trimmed.startsWith(".end param") || trimmed.startsWith(".prologue")) {
                    j++
                    continue
                }
                break
            }
            val span = MethodSpan(
                signature = sig,
                modifiers = mods,
                startLine = i,
                endLine = end,
                localsLine = localsIdx,
                localsCount = localsCount,
                bodyStartLine = j,
            )
            result.getOrPut(sig) { mutableListOf() }.add(span)
            i = end + 1
        }
        return result
    }

    fun ensureLocalsAtLeast(lines: MutableList<String>, span: MethodSpan, minLocals: Int) {
        if (span.localsLine >= 0 && span.localsCount < minLocals) {
            lines[span.localsLine] = "    .locals $minLocals"
        }
    }

    /**
     * Replaces an invoke line at [invokeIdx] with `nop` (so try ranges never become empty),
     * and if the next instruction is a `move-result*`, replaces that `move-result* <reg>`
     * with `const/4 <reg>, <constValHex>` so `OrphanMoveResultCheck` and ART verifier succeed.
     */
    fun dropInvokeAndResult(
        lines: MutableList<String>,
        invokeIdx: Int,
        comment: String,
        constValHex: String = "0x0",
    ): Boolean {
        lines[invokeIdx] = "    # $comment\n    nop"
        var j = invokeIdx + 1
        while (j < lines.size) {
            val s = lines[j].trim()
            if (s.isEmpty() || s.startsWith(".line") || s.startsWith(".local") || s.startsWith(".end local") || s.startsWith("#")) {
                j++
                continue
            }
            val mr = MOVE_RESULT_LINE.matchEntire(lines[j])
            if (mr != null) {
                val reg = mr.groupValues[1]
                lines[j] = "    const/4 $reg, $constValHex"
                return true
            }
            break
        }
        return false
    }
}
