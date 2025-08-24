package red.man10.man10bank.repository

import org.ktorm.database.Database
import org.ktorm.dsl.*
import org.ktorm.schema.year
import red.man10.man10bank.db.tables.EstateTbl
import red.man10.man10bank.db.tables.ServerEstateHistory
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

class UserEstateRepository(private val db: Database) {

    data class UserEstateParams(
        val uuid: String,
        val player: String,
        val vault: BigDecimal,
        val bank: BigDecimal,
        val cash: BigDecimal,
        val estate: BigDecimal,
        val loan: BigDecimal,
        val shop: BigDecimal,
        val crypto: BigDecimal,
        val time: ZonedDateTime? = null,
    ){
        fun total(): BigDecimal {
            return vault + bank + cash + estate + shop + crypto - loan
        }

        override fun equals(other: Any?): Boolean {
            if (other !is UserEstateParams) return false
            return uuid == other.uuid &&
                    player == other.player &&
                    vault.compareTo(other.vault) == 0 &&
                    bank.compareTo(other.bank) == 0 &&
                    cash.compareTo(other.cash) == 0 &&
                    estate.compareTo(other.estate) == 0 &&
                    loan.compareTo(other.loan) == 0 &&
                    shop.compareTo(other.shop) == 0 &&
                    crypto.compareTo(other.crypto) == 0
        }

        override fun hashCode(): Int =
            listOf(
                uuid, player,
                vault.stripTrailingZeros().toPlainString(),
                bank.stripTrailingZeros().toPlainString(),
                cash.stripTrailingZeros().toPlainString(),
                estate.stripTrailingZeros().toPlainString(),
                loan.stripTrailingZeros().toPlainString(),
                shop.stripTrailingZeros().toPlainString(),
                crypto.stripTrailingZeros().toPlainString()
            ).hashCode()
    }

    //TODO: 成功/失敗/重複の判定をちゃんとする
    fun updateAndAddHistory(params: UserEstateParams): Boolean {
        if (equalsLastEstate(params)) {
            return true
        }
        val updated = db.update(EstateTbl) {
            set(it.player, params.player)
            set(it.vault, params.vault)
            set(it.bank, params.bank)
            set(it.cash, params.cash)
            set(it.estate, params.estate)
            set(it.loan, params.loan)
            set(it.shop, params.shop)
            set(it.crypto, params.crypto)
            set(it.total, params.total())
            where { it.uuid eq params.uuid }
        }
        if (updated < 0) {
            return false
        }
        val inserted = db.insert(EstateTbl) {
            set(it.uuid, params.uuid)
            set(it.player, params.player)
            set(it.vault, params.vault)
            set(it.bank, params.bank)
            set(it.cash, params.cash)
            set(it.estate, params.estate)
            set(it.loan, params.loan)
            set(it.shop, params.shop)
            set(it.crypto, params.crypto)
            set(it.total, params.total())
        }
        return inserted == 1
    }

    fun getLast(uuid: String): UserEstateParams? {
        return db.from(EstateTbl)
            .select()
            .where { EstateTbl.uuid eq uuid }
            .orderBy(EstateTbl.id.desc())
            .limit(1)
            .map { row ->
                UserEstateParams(
                    uuid = row[EstateTbl.uuid] ?: "",
                    player = row[EstateTbl.player] ?: "",
                    vault = row[EstateTbl.vault] ?: BigDecimal.ZERO,
                    bank = row[EstateTbl.bank] ?: BigDecimal.ZERO,
                    cash = row[EstateTbl.cash] ?: BigDecimal.ZERO,
                    estate = row[EstateTbl.estate] ?: BigDecimal.ZERO,
                    loan = row[EstateTbl.loan] ?: BigDecimal.ZERO,
                    shop = row[EstateTbl.shop] ?: BigDecimal.ZERO,
                    crypto = row[EstateTbl.crypto] ?: BigDecimal.ZERO,
                    time = row[EstateTbl.date]?.atZone(ZoneId.systemDefault())
                )
            }.firstOrNull()
    }

    fun listOnDate(uuid: String, time: ZonedDateTime): List<UserEstateParams> {
        val start = time.toLocalDate().atStartOfDay(time.zone)
        val end = start.plusDays(1)

        return db.from(EstateTbl)
            .select()
            .where {
                EstateTbl.uuid eq uuid and
                (EstateTbl.date greaterEq start.toLocalDateTime()) and
                        (EstateTbl.date less end.toLocalDateTime())
            }.map { row ->
                UserEstateParams(
                    uuid = row[EstateTbl.uuid] ?: "",
                    player = row[EstateTbl.player] ?: "",
                    vault = row[EstateTbl.vault] ?: BigDecimal.ZERO,
                    bank = row[EstateTbl.bank] ?: BigDecimal.ZERO,
                    cash = row[EstateTbl.cash] ?: BigDecimal.ZERO,
                    estate = row[EstateTbl.estate] ?: BigDecimal.ZERO,
                    loan = row[EstateTbl.loan] ?: BigDecimal.ZERO,
                    shop = row[EstateTbl.shop] ?: BigDecimal.ZERO,
                    crypto = row[EstateTbl.crypto] ?: BigDecimal.ZERO,
                    time = row[EstateTbl.date]?.atZone(ZoneId.systemDefault())
                )
            }
    }

    private fun equalsLastEstate(params: UserEstateParams): Boolean {
        val lastEstateRecord = db.from(EstateTbl)
            .select()
            .where { EstateTbl.uuid eq params.uuid }
            .orderBy(EstateTbl.id.desc())
            .limit(1)
            .map { row ->
                UserEstateParams(
                    uuid = row[EstateTbl.uuid] ?: "",
                    player = row[EstateTbl.player] ?: "",
                    vault = row[EstateTbl.vault] ?: BigDecimal.ZERO,
                    bank = row[EstateTbl.bank] ?: BigDecimal.ZERO,
                    cash = row[EstateTbl.cash] ?: BigDecimal.ZERO,
                    estate = row[EstateTbl.estate] ?: BigDecimal.ZERO,
                    loan = row[EstateTbl.loan] ?: BigDecimal.ZERO,
                    shop = row[EstateTbl.shop] ?: BigDecimal.ZERO,
                    crypto = row[EstateTbl.crypto] ?: BigDecimal.ZERO,
                )
            }.firstOrNull() ?: return false

        return params == lastEstateRecord
    }
}