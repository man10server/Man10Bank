package red.man10.man10bank.command.op.sub.edit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import red.man10.man10bank.service.vault.VaultService

/**
 * /bankop editvault <player> <金額> <理由> - 指定プレイヤーの電子マネー残高を調整。
 *
 * オフライン（当該サーバー未キャッシュ）プレイヤーの電子マネーを変更できる唯一の正規経路として
 * サーバー側の絶対値設定 POST /api/Vault/set（行ロック下で差分ログ＋version++＋push を原子的に実行）へ
 * 委譲する（VaultProvider 4.5）。Vault(Economy) の deposit/withdraw は未キャッシュ対象を拒否するため使わない。
 */
class EditVaultSubcommand(
    private val scope: CoroutineScope,
    private val vaultService: VaultService,
) : EditBalanceSubcommand(
    name = "editvault",
    usageDescription = "電子マネー残高を指定額に調整",
) {
    override fun executeEdit(sender: CommandSender, target: EditTarget) {
        scope.launch {
            vaultService.setBalance(
                sender = sender,
                targetUuid = target.offline.uniqueId,
                targetName = target.displayName,
                amount = target.amount,
                reason = target.reason,
            )
        }
    }
}
