package red.man10.man10bank.repository

import org.ktorm.database.Database
import org.ktorm.dsl.*
import red.man10.man10bank.db.tables.ServerEstateHistory
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

class ServerEstateRepository(private val db: Database) {

    data class ServerEstateParams(
        val vault: BigDecimal,
        val bank: BigDecimal,
        val cash: BigDecimal,
        val estate: BigDecimal,
        val loan: BigDecimal,
        val shop: BigDecimal,
        val crypto: BigDecimal,
        val time: ZonedDateTime? = null,
    ){
        fun total(): BigDecimal =
            vault
                .add(bank)
                .add(cash)
                .add(estate)
                .add(shop)
                .add(crypto)
                .subtract(loan)

        override fun equals(other: Any?): Boolean {
            if (other !is ServerEstateParams) return false
            return  vault.compareTo(other.vault) == 0 &&
                    bank.compareTo(other.bank) == 0 &&
                    cash.compareTo(other.cash) == 0 &&
                    estate.compareTo(other.estate) == 0 &&
                    loan.compareTo(other.loan) == 0 &&
                    shop.compareTo(other.shop) == 0 &&
                    crypto.compareTo(other.crypto) == 0
        }

        override fun hashCode(): Int =
            listOf(
                vault.stripTrailingZeros().toPlainString(),
                bank.stripTrailingZeros().toPlainString(),
                cash.stripTrailingZeros().toPlainString(),
                estate.stripTrailingZeros().toPlainString(),
                loan.stripTrailingZeros().toPlainString(),
                shop.stripTrailingZeros().toPlainString(),
                crypto.stripTrailingZeros().toPlainString()
            ).hashCode()
    }

    fun updateAndAddHistory(params: ServerEstateParams): Boolean {
        val now = ZonedDateTime.now()
        if (isRecorded(now)) {
            return true
        }
        val inserted = db.insert(ServerEstateHistory) {
            set(it.vault, params.vault)
            set(it.bank, params.bank)
            set(it.cash, params.cash)
            set(it.estate, params.estate)
            set(it.loan, params.loan)
            set(it.shop, params.shop)
            set(it.crypto, params.crypto)
            set(it.total, params.total())
            set(it.year, now.year)
            set(it.month, now.monthValue)
            set(it.day, now.dayOfMonth)
            set(it.hour, now.hour)
            set(it.date, now.toLocalDateTime())
        }
        return inserted == 1
    }

    fun getLast(): ServerEstateParams? {
        return db.from(ServerEstateHistory)
            .select()
            .orderBy(ServerEstateHistory.id.desc())
            .limit(1)
            .map {
                ServerEstateParams(
                    vault = it[ServerEstateHistory.vault] ?: BigDecimal.ZERO,
                    bank = it[ServerEstateHistory.bank] ?: BigDecimal.ZERO,
                    cash = it[ServerEstateHistory.cash] ?: BigDecimal.ZERO,
                    estate = it[ServerEstateHistory.estate] ?: BigDecimal.ZERO,
                    loan = it[ServerEstateHistory.loan] ?: BigDecimal.ZERO,
                    shop = it[ServerEstateHistory.shop] ?: BigDecimal.ZERO,
                    crypto = it[ServerEstateHistory.crypto] ?: BigDecimal.ZERO,
                    time = it[ServerEstateHistory.date]?.atZone(ZoneId.systemDefault())
                )
            }.firstOrNull()
    }

    fun listOnDate(time: ZonedDateTime): List<ServerEstateParams> {
        return db.from(ServerEstateHistory)
            .select()
            .where {
                (ServerEstateHistory.year eq time.year) and
                        (ServerEstateHistory.month eq time.monthValue) and
                        (ServerEstateHistory.day eq time.dayOfMonth)
            }.map {
                ServerEstateParams(
                    vault = it[ServerEstateHistory.vault] ?: BigDecimal.ZERO,
                    bank = it[ServerEstateHistory.bank] ?: BigDecimal.ZERO,
                    cash = it[ServerEstateHistory.cash] ?: BigDecimal.ZERO,
                    estate = it[ServerEstateHistory.estate] ?: BigDecimal.ZERO,
                    loan = it[ServerEstateHistory.loan] ?: BigDecimal.ZERO,
                    shop = it[ServerEstateHistory.shop] ?: BigDecimal.ZERO,
                    crypto = it[ServerEstateHistory.crypto] ?: BigDecimal.ZERO,
                    time = it[ServerEstateHistory.date]?.atZone(ZoneId.systemDefault())
                )
            }
    }

    private fun isRecorded(time: ZonedDateTime): Boolean {
        val count = db.from(ServerEstateHistory)
            .select(count())
            .where {
                (ServerEstateHistory.year eq time.year) and
                        (ServerEstateHistory.month eq time.monthValue) and
                        (ServerEstateHistory.day eq time.dayOfMonth) and
                        (ServerEstateHistory.hour eq time.hour)
            }
            .map { it.getInt(1) }
            .firstOrNull() ?: 0
        return count >= 1
    }
}
