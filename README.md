# FoliaMarket

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Paper / Folia](https://img.shields.io/badge/Platform-Paper%20%7C%20Folia-brightgreen.svg)](https://papermc.io)
[![Java: 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)
[![CI](https://github.com/QuQuLa89/folia-market/actions/workflows/ci.yml/badge.svg)](https://github.com/QuQuLa89/folia-market/actions/workflows/ci.yml)

**FoliaMarket** は、Minecraft サーバー（Paper 1.21.4+ およびリージョン分割マルチスレッドサーバー **Folia**）に対応した GUI 型マーケットプレイスプラグインです。

---

## 主な特徴

- **完全なマルチスレッド / Folia 対応**:
  - Folia のリージョンスレッドモデルに対応した `SchedulerAdapter` を搭載しています。
  - プレイヤーのインベントリ操作やアイテム付与は、該当エンティティのリージョンスレッドで安全に行われます。
  - Paper 環境では自動的に Bukkit スケジューラへフォールバックするため、単一の JAR で両環境に対応します。
- **排他制御による取引エンジン (CAS)**:
  - データベース操作に Compare-And-Swap（CAS: `UPDATE ... WHERE id = ? AND status = 'ACTIVE'`）を採用し、複数プレイヤーによる同一アイテムへの同時購入競合を完全に防止します。
- **チラつきのない差分更新 GUI**:
  - インベントリ全体を再描画せず、変更があったスロットのみを差分更新するため、画面のチラつきやカーソルのリセットが発生しません。
- **Minecraft 1.21+ Data Components の完全保持**:
  - 新形式の Data Components をバイト配列としてシリアライズし、カスタムネーム、エンチャント、属性、内部データなどを一切損なうことなく売買できます。
- **公式日本語名称によるネイティブ検索（F3 + H 不要）**:
  - Minecraft 1.21.4 公式の日本語言語データ（約 7,000 語）を内包しています。
  - 日本語名（「ダイヤ」「剣」など）、英語マテリアル名（`diamond`、`sword` など）、金床によるカスタム名のいずれでも部分一致検索（`/market search <キーワード>`、短縮形 `/market s`）が可能です。
- **直感的なワンクリック・ソート機能**:
  - GUI 最下段スロット 51 の「並び替え」アイコンをクリックすることで、「新着順」「価格が安い順」「価格が高い順」「期限が近い順」の 4 つのソート順を循環して切り替えられます。
- **出品取り下げ確認ダイアログ**:
  - 自分の出品一覧からアイテムを取り下げる際、確認画面（確定 / 戻る）が表示され、誤操作による意図しない回収を防止します。
- **所持金のリアルタイム表示**:
  - GUI 最下段スロット 47 にプレイヤー自身の所持金が金インゴットアイコンとして常時表示されます。
- **高パフォーマンスな接続プール**:
  - HikariCP と SQLite の WAL（Write-Ahead Logging）モードを組み合わせ、非同期かつ高速なデータアクセスを実現しています。
- **Vault 経済プラグイン連携**:
  - Vault / VaultUnlocked を介して、各種経済プラグイン（EssentialsX、FoliaEconomyX など）との送金・引き落とし・売上通知に対応します。

---

## 必要環境

- **サーバー環境**: Paper 1.21.4+ または Folia 1.21.4+
- **Java**: Java 21 以上
- **経済プラグイン**:
  - Paper 環境: [Vault](https://www.spigotmc.org/resources/vault.34315/) + [EssentialsX](https://essentialsx.net/) 等
  - Folia 環境: [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked) + [FoliaEconomyX](https://modrinth.com/plugin/folia-economy) 等
  - ※ 経済プラグインが存在しない場合でも、ダミーモードとして安全に起動します。

---

## コマンド一覧

| コマンド | 説明 | 必要権限 |
|---|---|---|
| `/market` (別名: `/fm`, `/foliamarket`) | マーケットプレイスのメイン GUI を開きます。 | `foliamarket.use` |
| `/market search <キーワード>` (別名: `/market s`) | 日本語名・英語名・出品者名で出品中の商品を検索します。 | `foliamarket.use` |
| `/market sell <価格>` | メインハンドに持っているアイテムを指定価格で出品します。 | `foliamarket.sell` |
| `/market listings` (別名: `/market my`) | 自分が出品した商品の一覧を開き、出品を取り下げることができます。 | `foliamarket.use` |
| `/market reload` | 設定ファイル（`config.yml`）を再読み込みします。 | `foliamarket.admin` |
| `/market debug stress <出品ID> [スレッド数]` | 指定した出品に対して複数スレッドによる並行購入ストレステストを実行し、CAS 排他制御とアイテム増殖ゼロを検証します。テスト後、アイテムは自動復元されます。 | `foliamarket.admin` |

---

## 権限一覧

- `foliamarket.use`: マーケットプレイスの閲覧および購入権限（デフォルト: 一般プレイヤー）
- `foliamarket.sell`: アイテムを出品する権限（デフォルト: 一般プレイヤー）
- `foliamarket.admin`: 管理コマンドの実行権限（デフォルト: OP）

---

## ビルド手順

本プラグインは Gradle (Kotlin DSL) を使用してビルドできます。

### 1. リポジトリのクローン
```bash
git clone https://github.com/QuQuLa89/folia-market.git
cd folia-market
```

### 2. コードスタイルの検証 (ktlint)
```bash
./gradlew ktlintCheck
```

### 3. シャドウ JAR のビルド
```bash
./gradlew build
```

ビルドが完了すると、`build/libs/folia-market-1.0.0.jar` に実行可能 JAR ファイルが生成されます。

---

## ライセンス

本プロジェクトは [MIT License](LICENSE) の下で公開されています。
開発者: **QuQuLa89** (2026)
