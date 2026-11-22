package red.man10.man10bank.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * 要手動対応になったアイテム（返せなかった手形・小切手・担保など）の復元台帳。
 * - 1件1ファイル（`<dir>/<id>.json`）で保存し、運営が /bankop loan recovery で確認・復元・破棄する。
 * - 同じ記録から二重に渡さないよう状態を持つ。渡す側は先に「復元済み」を保存してから渡すこと
 *   （逆順だと、渡した直後に落ちた場合にもう一度渡せてしまう）。
 * - 記録は失敗した箇所（メインスレッド以外を含む）から呼ばれるため、操作はすべて同期化する。
 * - アイテムは ItemStackBase64.encodeItems 形式の文字列で持つ（このクラスは Bukkit に依存しない）。
 */
class RecoveryLedger(private val dir: File, private val logger: Logger) {

    /** [itemLabel] は受け取るプレイヤー向けのアイテムの呼び名。 */
    enum class Type(val label: String, val itemLabel: String) {
        NOTE_RETURN_FAILED("手形返却失敗", "手形"),
        CHEQUE_RETURN_FAILED("小切手返却失敗", "小切手"),
        COLLATERAL_RETURN_FAILED("担保返却失敗", "担保"),
        COLLATERAL_HANDOVER_FAILED("担保受け渡し失敗", "担保"),
        LOAN_CREATE_INDETERMINATE("ローン作成結果不明", "担保"),
    }

    enum class Status(val label: String) {
        PENDING("未対応"),
        RESTORED("復元済み"),
        DISCARDED("破棄"),
    }

    @Serializable
    data class Record(
        val id: String,
        val type: Type,
        /** アイテムを本来受け取るはずだったプレイヤーの UUID */
        val owner: String,
        val loanId: Int? = null,
        /** ローン作成結果不明のときの貸し手の UUID */
        val lender: String? = null,
        val itemsBase64: String,
        /** スタック数 */
        val itemCount: Int,
        val detail: String,
        val createdAt: String,
        val status: Status = Status.PENDING,
        val resolvedAt: String? = null,
        val resolvedBy: String? = null,
        /** 復元したときに渡した相手の UUID */
        val resolvedTo: String? = null,
        /** 破棄した理由 */
        val resolveNote: String? = null,
    ) {
        val ownerUuid: UUID get() = UUID.fromString(owner)
        val lenderUuid: UUID? get() = lender?.let(UUID::fromString)
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 記録して ID を返す。保存できなかった場合は null を返す
     * （呼び出し側の要手動対応ログに出すアイテムデータが唯一の控えになる）。
     */
    @Synchronized
    fun record(
        type: Type,
        owner: UUID,
        itemsBase64: String,
        itemCount: Int,
        detail: String,
        loanId: Int? = null,
        lender: UUID? = null,
    ): String? = try {
        dir.mkdirs()
        val record = Record(
            id = newId(),
            type = type,
            owner = owner.toString(),
            loanId = loanId,
            lender = lender?.toString(),
            itemsBase64 = itemsBase64,
            itemCount = itemCount,
            detail = detail,
            createdAt = Instant.now().toString(),
        )
        save(record)
        record.id
    } catch (e: Exception) {
        logger.severe("復元台帳への記録に失敗しました: type=${type.name} owner=${owner} ${e.message}")
        null
    }

    @Synchronized
    fun get(id: String): Record? {
        if (!ID_PATTERN.matches(id)) return null
        val file = fileOf(id)
        if (!file.isFile) return null
        return load(file)
    }

    /** [status] の記録を古い順に返す（null なら全件）。 */
    @Synchronized
    fun list(status: Status? = Status.PENDING): List<Record> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { load(it) }
            .filter { status == null || it.status == status }
            .sortedBy { it.createdAt }

    /** 未対応の記録を「復元済み」にして保存する。未対応でない・保存できない場合は失敗（アイテムを渡してはいけない）。 */
    @Synchronized
    fun markRestored(id: String, by: String, to: UUID): Result<Record> = resolve(id) {
        it.copy(status = Status.RESTORED, resolvedAt = Instant.now().toString(), resolvedBy = by, resolvedTo = to.toString())
    }

    /** 未対応の記録を、アイテムを渡さずに「破棄」にして保存する。 */
    @Synchronized
    fun markDiscarded(id: String, by: String, reason: String): Result<Record> = resolve(id) {
        it.copy(status = Status.DISCARDED, resolvedAt = Instant.now().toString(), resolvedBy = by, resolveNote = reason)
    }

    private fun resolve(id: String, update: (Record) -> Record): Result<Record> {
        val current = get(id) ?: return Result.failure(IllegalArgumentException("記録が見つかりません: $id"))
        if (current.status != Status.PENDING) {
            return Result.failure(IllegalStateException("この記録は既に${current.status.label}です。"))
        }
        val updated = update(current)
        return runCatching {
            save(updated)
            updated
        }
    }

    // 書きかけのファイルが残らないよう、一時ファイルに書いてから置き換える
    private fun save(record: Record) {
        val tmp = File(dir, "${record.id}.json.tmp")
        tmp.writeText(json.encodeToString(Record.serializer(), record), Charsets.UTF_8)
        Files.move(tmp.toPath(), fileOf(record.id).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(file: File): Record? =
        runCatching { json.decodeFromString(Record.serializer(), file.readText(Charsets.UTF_8)) }
            .onFailure { logger.warning("復元台帳の読み込みに失敗しました: ${file.name} ${it.message}") }
            .getOrNull()

    private fun newId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().substring(0, 8)
            if (!fileOf(id).exists()) return id
        }
    }

    private fun fileOf(id: String): File = File(dir, "$id.json")

    companion object {
        private val ID_PATTERN = Regex("^[0-9a-f]{8}$")
    }
}
