# APIエラーハンドリング標準化（ProblemDetails）

## 背景/要件
- WebAPI(ASP.NET)側は非200時に ProblemDetails を返す（`{type,title,status,detail,instance}`）。
- プレイヤー/コンソールへは例外全文ではなく、エラー理由（短文）だけを表示したい。
- 既存の `BankApiClient` の409など個別分岐は段階的に撤廃し、横断で統一。

## 実装概要
- HttpClientFactory に例外正規化を集約。
  - `HttpResponseValidator { handleResponseExceptionWithRequest { … } }` で `ClientRequestException` を捕捉。
  - レスポンスボディを `ProblemDetails` にデコードし、`ApiHttpException(status, problem, message)` に置換してthrow。
  - `message` は `problem.title` → `problem.detail` → 既定文言（400/409/401/403/404/その他）を使用。
  - `Json` のインスタンスは `jsonFormat` を共有して再利用（警告: Redundant creation of Json format を解消）。
- APIクライアント（Loan/Bank）は `runCatching { client.xxx.body() }` の簡素実装に戻す。
  - 非200時はFactoryで `ApiHttpException` へ変換されるため、クライアント側の個別ハンドリングは不要。
- サービス層は Result の例外 `message` をそのまま表示（短文）に統一。
  - 既存で分岐していた `InsufficientBalanceException` などの個別分岐を撤廃。

## 影響ファイル
- 追加:
  - `src/main/java/red/man10/man10bank/api/error/ProblemDetails.kt`
  - `src/main/java/red/man10/man10bank/api/error/ApiHttpException.kt`
- 更新:
  - `src/main/java/red/man10/man10bank/net/HttpClientFactory.kt`
    - HttpResponseValidator導入、ProblemDetails→ApiHttpException化、`jsonFormat`共通化。
  - `src/main/java/red/man10/man10bank/api/LoanApiClient.kt`（簡素化/復帰）
  - `src/main/java/red/man10/man10bank/api/BankApiClient.kt`（簡素化/復帰）
  - `src/main/java/red/man10/man10bank/service/BankService.kt`（例外メッセージ表示に統一）
- 既存変更（関連）:
  - `LoanService.create`: 成功/失敗のみ返す（副作用は内部で実施）。
  - `LendCommand.handleLenderConfirm`: 成功時は提案破棄のみ／失敗時は担保返却＋提案破棄。

## 表示仕様
- プレイヤー/コンソール: `exception.message`（=ProblemDetails.title または既定短文）を表示。
- 必要であれば、将来的にOP/コンソール向けには `problem.instance` や `status` の追加出力も可能。

## 補足/留意
- `BankApiClient.getBalance` の `Json.parseToJsonElement(text)` は当面そのまま（必要なら `jsonFormat` に寄せて最適化可）。
- ネットワーク不可/タイムアウト等の非HTTP例外を別文言に正規化する拡張余地あり（FactoryでIOException系を包む）。
- 他API（Atm/Cheques/Estate/ServerLoanなど）もFactoryの統一処理が効くため、サービス層は例外メッセージ表示のみで整合。