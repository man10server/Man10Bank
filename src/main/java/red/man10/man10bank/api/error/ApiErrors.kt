package red.man10.man10bank.api.error

/**
 * APIクライアントで利用する例外定義。
 */
class InsufficientBalanceException(
    message: String = "残高不足により出金できません"
) : Exception(message)

