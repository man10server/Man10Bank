package red.man10.man10bank.util

import kotlinx.coroutines.CompletableDeferred
import org.bukkit.plugin.java.JavaPlugin

/**
 * メインスレッド実行ヘルパー。
 * Bukkit/Vault API はメインスレッド以外からの呼び出しが安全でないため、コルーチン（IOスレッド）からは
 * これ経由で呼び、結果を待つ（DESIGN 3.5）。既にメインスレッドなら即時実行する。
 */
suspend fun <T> JavaPlugin.runOnMainThread(block: () -> T): T {
    if (server.isPrimaryThread) return block()
    val deferred = CompletableDeferred<T>()
    server.scheduler.runTask(this, Runnable {
        try {
            deferred.complete(block())
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
        }
    })
    return deferred.await()
}
