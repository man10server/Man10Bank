package red.man10.man10bank.service

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.bukkit.Bukkit
import org.ktorm.database.Database
import red.man10.man10bank.repository.BankRepository
import red.man10.man10bank.repository.BankRepository.LogParams
import red.man10.man10bank.model.OperationResult
import red.man10.man10bank.model.ResultCode
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executors

class BankService(db: Database, serverName: String = Bukkit.getServer().name) {

    private val repository = BankRepository(db, serverName)

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bank-service-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val queue = Channel<Op>(Channel.UNLIMITED)

    private sealed class Op {
        data class SetBalance(
            val uuid: UUID,
            val amount: BigDecimal,
            val pluginName: String,
            val note: String,
            val displayNote: String?,
            val result: CompletableDeferred<OperationResult>
        ) : Op()
        data class Deposit(
            val uuid: UUID,
            val amount: BigDecimal,
            val pluginName: String,
            val note: String,
            val displayNote: String?,
            val result: CompletableDeferred<OperationResult>
        ) : Op()

        data class Withdraw(
            val uuid: UUID,
            val amount: BigDecimal,
            val pluginName: String,
            val note: String,
            val displayNote: String?,
            val result: CompletableDeferred<OperationResult>
        ) : Op()

        data class Transfer(
            val fromUuid: UUID,
            val toUuid: UUID,
            val amount: BigDecimal,
            val result: CompletableDeferred<OperationResult>
        ) : Op()
    }

    init {
        // ワーカー起動：常に1件ずつ順次処理
        scope.launch {
            for (op in queue) {
                when (op) {
                    is Op.SetBalance -> handleSetBalance(op)
                    is Op.Deposit -> handleDeposit(op)
                    is Op.Withdraw -> handleWithdraw(op)
                    is Op.Transfer -> handleTransfer(op)
                }
            }
        }
    }

    suspend fun getBalance(uuid: UUID): BigDecimal? = withContext(dispatcher) {
        repository.getBalanceByUuid(uuid.toString())
    }

    suspend fun setBalance(
        uuid: UUID,
        amount: BigDecimal,
        pluginName: String,
        note: String,
        displayNote: String?,
    ): OperationResult {
        val deferred = CompletableDeferred<OperationResult>()
        queue.send(Op.SetBalance(uuid, amount, pluginName, note, displayNote, deferred))
        return deferred.await()
    }

    suspend fun deposit(
        uuid: UUID,
        amount: BigDecimal,
        pluginName: String,
        note: String,
        displayNote: String?,
    ): OperationResult {
        val deferred = CompletableDeferred<OperationResult>()
        queue.send(Op.Deposit(uuid, amount, pluginName, note, displayNote, deferred))
        return deferred.await()
    }

    suspend fun withdraw(
        uuid: UUID,
        amount: BigDecimal,
        pluginName: String,
        note: String,
        displayNote: String?,
    ): OperationResult {
        val deferred = CompletableDeferred<OperationResult>()
        queue.send(Op.Withdraw(uuid, amount, pluginName, note, displayNote, deferred))
        return deferred.await()
    }

    suspend fun transfer(
        fromUuid: UUID,
        toUuid: UUID,
        amount: BigDecimal,
    ): OperationResult {
        val deferred = CompletableDeferred<OperationResult>()
        queue.send(Op.Transfer(fromUuid, toUuid, amount, deferred))
        return deferred.await()
    }

    suspend fun getLog(uuid: UUID, limit: Int, offset: Int): List<LogParams> = withContext(dispatcher) {
        repository.getLog(uuid.toString(), limit, offset)
    }

    fun shutdown() {
        scope.cancel()
        dispatcher.close()
    }

    private fun handleSetBalance(op: Op.SetBalance) {
        val (uuid, amount, pluginName, note, displayNote, result) = op
        try {
            if (amount.signum() < 0) {
                result.complete(OperationResult(ResultCode.INVALID_AMOUNT))
                return
            }
            val uuidStr = uuid.toString()
            val playerName = Bukkit.getOfflinePlayer(uuid).name ?: ""
            val current = repository.getBalanceByUuid(uuidStr) ?: BigDecimal.ZERO
            val delta = amount.subtract(current)
            val next = if (delta.compareTo(BigDecimal.ZERO) == 0) {
                current
            } else if (delta > BigDecimal.ZERO) {
                repository.increaseBalance(
                    uuid = uuidStr,
                    player = playerName,
                    amount = delta,
                    log = LogParams(
                        pluginName = pluginName,
                        note = note,
                        displayNote = displayNote ?: note,
                    )
                )
            } else {
                repository.decreaseBalance(
                    uuid = uuidStr,
                    player = playerName,
                    amount = delta.abs(),
                    log = LogParams(
                        pluginName = pluginName,
                        note = note,
                        displayNote = displayNote ?: note,
                    )
                )
            }
            result.complete(OperationResult(ResultCode.SUCCESS, next))
        } catch (t: Throwable) {
            result.complete(OperationResult(ResultCode.FAILURE))
        }
    }

    private fun handleDeposit(op: Op.Deposit) {
        val (uuid, amount, pluginName, note, displayNote, result) = op
        try {
            if (amount.signum() <= 0) {
                result.complete(OperationResult(ResultCode.INVALID_AMOUNT))
                return
            }
            val uuidStr = uuid.toString()
            val playerName = Bukkit.getOfflinePlayer(uuid).name ?: ""
            val next = repository.increaseBalance(
                uuid = uuidStr,
                player = playerName,
                amount = amount,
                log = LogParams(
                    pluginName = pluginName,
                    note = note,
                    displayNote = displayNote ?: note,
                )
            )
            result.complete(OperationResult(ResultCode.SUCCESS, next))
        } catch (t: Throwable) {
            result.complete(OperationResult(ResultCode.FAILURE))
        }
    }

    private fun handleWithdraw(op: Op.Withdraw) {
        val (uuid, amount, pluginName, note, displayNote, result) = op
        try {
            if (amount.signum() <= 0) {
                result.complete(OperationResult(ResultCode.INVALID_AMOUNT))
                return
            }
            val uuidStr = uuid.toString()
            val playerName = Bukkit.getOfflinePlayer(uuid).name ?: ""
            val current = repository.getBalanceByUuid(uuidStr) ?: BigDecimal.ZERO
            if (current < amount) {
                result.complete(OperationResult(ResultCode.INSUFFICIENT_FUNDS))
                return
            }
            val next = repository.decreaseBalance(
                uuid = uuidStr,
                player = playerName,
                amount = amount,
                log = LogParams(
                    pluginName = pluginName,
                    note = note,
                    displayNote = displayNote ?: note,
                )
            )
            result.complete(OperationResult(ResultCode.SUCCESS, next))
        } catch (t: Throwable) {
            result.complete(OperationResult(ResultCode.FAILURE))
        }
    }

    private fun handleTransfer(op: Op.Transfer) {
        val (fromUuid, toUuid, amount, result) = op
        try {
            if (amount.signum() <= 0) {
                result.complete(OperationResult(ResultCode.INVALID_AMOUNT))
                return
            }
            val fromUuidStr = fromUuid.toString()
            val toUuidStr = toUuid.toString()
            val fromPlayer = Bukkit.getOfflinePlayer(fromUuid).name ?: ""
            val toPlayer = Bukkit.getOfflinePlayer(toUuid).name ?: ""

            val fromCurrent = repository.getBalanceByUuid(fromUuidStr) ?: BigDecimal.ZERO
            if (fromCurrent < amount) {
                result.complete(OperationResult(ResultCode.INSUFFICIENT_FUNDS))
                return
            }

            repository.transferBetweenUsers(
                fromUuid = fromUuidStr,
                fromPlayer = fromPlayer,
                toUuid = toUuidStr,
                toPlayer = toPlayer,
                amount = amount
            )
            val fromAfter = repository.getBalanceByUuid(fromUuidStr)
            result.complete(OperationResult(ResultCode.SUCCESS, fromAfter))
        } catch (t: Throwable) {
            result.complete(OperationResult(ResultCode.FAILURE))
        }
    }
}
