# FoliaMarket

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Paper / Folia](https://img.shields.io/badge/Platform-Paper%20%7C%20Folia-brightgreen.svg)](https://papermc.io)
[![Java: 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)

**FoliaMarket** は、Minecraft サーバー（Paper 1.21.4+ およびマルチスレッドサーバー **Folia**）に対応した、次世代のGUI型マーケットプレイスプラグインです。

---

## 🌟 主な特徴

- **完全なマルチスレッド / Folia 対応**:
  - Folia のリージョンスレッドモデルに対応した `SchedulerAdapter` を搭載しています。
  - プレイヤーのインベントリ操作やアイテム付与は該当エンティティのリージョンスレッドで安全に行われます。
  - Paper 環境では自動的に Bukkit スケジューラへフォールバックするため、単一の JAR で両環境に対応します。
- **スレッドセーフなトランザクションエンジン (CAS)**:
  - データベース操作に Compare-And-Swap（CAS: `UPDATE ... WHERE id = ? AND status = 'ACTIVE'`）を採用し、複数プレイヤーによる同一アイテムへの同時購入競合（Race Condition）を完全に防止します。
- **チラつきのないリアクティブ GUI**:
  - インベントリ全体を再描画するのではなく、変更があったスロットのみを差分更新（Differential Slot Updates）するため、画面のチラつきやカーソルのリセットが発生しません。
- **Minecraft 1.21+ Data Components 完全保持**:
  - 新形式の Data Components をバイト配列としてシリアライズし、カスタムネーム、エンチャント、属性、内部データなどを一切欠損させることなく売買できます。
- **高パフォーマンスな接続プール**:
  - HikariCP と SQLite の WAL（Write-Ahead Logging）モードを組み合わせ、非同期かつ高速なデータアクセスを実現しています。
- **Vault 経済プラグイン連携**:
  - Vault を通じた柔軟な経済システム（EssentialsX 等）との送金・引き落とし・売上通知に対応しています。

---

## 📦 必要環境

- **サーバー環境**: Paper 1.21.4+ または Folia 1.21.4+
- **Java**: Java 21 以上
- **前提プラグイン**:
  - [Vault](https://www.spigotmc.org/resources/vault.34315/)（推奨。経済機能を利用する場合）

---

## 💻 コマンド一覧

| コマンド | 説明 | 必要権限 |
|---|---|---|
| `/market` (別名: `/fm`, `/foliamarket`) | マーケットプレイスのメインGUIを開きます。 | `foliamarket.use` |
| `/market sell <価格>` | メインハンドに持っているアイテムを指定価格で出品します。 | `foliamarket.sell` |
| `/market listings` (別名: `/market my`) | 自分が出品した商品の一覧を開き、出品を取り下げることができます。 | `foliamarket.use` |
| `/market reload` | `config.yml` を再読み込みします。 | `foliamarket.admin` |

---

## 🔑 権限ノード

- `foliamarket.use`: マーケットプレイスの閲覧および購入権限（デフォルト: 一般プレイヤー）
- `foliamarket.sell`: アイテムを出品する権限（デフォルト: 一般プレイヤー）
- `foliamarket.admin`: 管理コマンド（設定リロード等）の実行権限（デフォルト: OP）

---

## 🛠️ ビルド手順

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

### 3. シャドウJARのビルド
```bash
./gradlew build
```

ビルドが完了すると、`build/libs/folia-market-1.0.0.jar` に実行可能 JAR ファイルが生成されます。
この JAR ファイルをサーバーの `plugins/` フォルダに配置してサーバーを起動してください。

---

## 📜 ライセンス

本プロジェクトは [MIT License](LICENSE) の下で公開されています。
開発者: **QuQuLa89** (2026)
