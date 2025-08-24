package red.man10.man10bank.service

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import red.man10.man10bank.model.OperationResult
import red.man10.man10bank.model.ResultCode
import red.man10.man10bank.util.BigDecimalConverter
import java.math.BigDecimal
import java.util.UUID

class VaultEconomyService(
    private val economy: Economy
) {

    companion object {
        fun create(): VaultEconomyService? {
            val econ = setupEconomy() ?: return null
            return VaultEconomyService(econ)
        }

        private fun setupEconomy(): Economy? {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null
            val reg = Bukkit.getServer().servicesManager.getRegistration(Economy::class.java) ?: return null
            return reg.provider
        }

        private fun offline(uuid: UUID): OfflinePlayer = Bukkit.getOfflinePlayer(uuid)
    }

    fun getBalance(uuid: UUID): BigDecimal {
        val bal = economy.getBalance(offline(uuid))
        return BigDecimalConverter.fromDouble(bal)
    }

    fun deposit(uuid: UUID, amount: BigDecimal): OperationResult {
        if (amount <= BigDecimal.ZERO) {
            return OperationResult(ResultCode.INVALID_AMOUNT)
        }
        return try {
            val res = economy.depositPlayer(offline(uuid), BigDecimalConverter.toDouble(amount))
            if (!res.transactionSuccess()) {
                OperationResult(ResultCode.FAILURE)
            } else {
                OperationResult(ResultCode.SUCCESS, getBalance(uuid))
            }
        } catch (t: IllegalArgumentException) {
            OperationResult(ResultCode.OVERFLOW)
        } catch (t: Throwable) {
            OperationResult(ResultCode.FAILURE)
        }
    }

    fun withdraw(uuid: UUID, amount: BigDecimal): OperationResult {
        if (amount <= BigDecimal.ZERO) {
            return OperationResult(ResultCode.INVALID_AMOUNT)
        }
        val current = getBalance(uuid)
        if (current < amount) {
            return OperationResult(ResultCode.INSUFFICIENT_FUNDS)
        }
        return try {
            val res = economy.withdrawPlayer(offline(uuid), BigDecimalConverter.toDouble(amount))
            if (!res.transactionSuccess()) {
                OperationResult(ResultCode.FAILURE)
            } else {
                OperationResult(ResultCode.SUCCESS, getBalance(uuid))
            }
        } catch (t: IllegalArgumentException) {
            OperationResult(ResultCode.OVERFLOW)
        } catch (t: Throwable) {
            OperationResult(ResultCode.FAILURE)
        }
    }

    fun has(uuid: UUID, amount: BigDecimal): Boolean {
        if (amount <= BigDecimal.ZERO) return true
        val current = getBalance(uuid)
        return current >= amount
    }
}
