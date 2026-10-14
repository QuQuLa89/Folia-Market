package com.ququla89.foliamarket.engine

import com.ququla89.foliamarket.database.MarketDao
import com.ququla89.foliamarket.database.model.MarketItemStatus
import com.ququla89.foliamarket.scheduler.SchedulerAdapter
import com.ququla89.foliamarket.util.MessageUtil
import net.kyori.adventure.audience.Audience
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Result data holder for concurrent stress tests.
 */
data class StressTestResult(
    val itemId: Long,
    val threadCount: Int,
    val successCount: Int,
    val alreadySoldCount: Int,
    val errorCount: Int,
    val elapsedMs: Long,
    val winnerName: String?,
) {
    val isPassed: Boolean get() = successCount == 1
    val isDupeDetected: Boolean get() = successCount > 1
    val isFailed: Boolean get() = successCount == 0
}

/**
 * Service orchestrating concurrent stress testing to verify Compare-And-Swap (CAS)
 * concurrency control and prevent duplicate item purchases (dupe bugs).
 */
class StressTestService(
    private val dao: MarketDao,
    private val scheduler: SchedulerAdapter? = null,
    private val logger: Logger = Logger.getLogger("StressTest"),
    private val plugin: Plugin? = null,
) {
    constructor(
        plugin: Plugin,
        dao: MarketDao,
        scheduler: SchedulerAdapter,
        logger: Logger,
    ) : this(dao, scheduler, logger, plugin)

    /**
     * Runs the stress test asynchronously to prevent blocking server threads.
     */
    fun runStressTest(
        sender: Audience,
        itemId: Long,
        threadCount: Int,
        prefix: String = "",
    ): CompletableFuture<StressTestResult> {
        val future = CompletableFuture<StressTestResult>()

        val task: () -> Unit = task@{
            try {
                // 1. Verify target item existence & status
                val targetItem = dao.getItemById(itemId)
                if (targetItem == null) {
                    MessageUtil.send(
                        sender,
                        "<red>指定された出品ID (<b>#$itemId</b>) が見つかりませんでした。</red>",
                        prefix = prefix,
                    )
                    future.complete(
                        StressTestResult(itemId, threadCount, 0, 0, 1, 0L, null),
                    )
                    return@task
                }

                if (targetItem.status != MarketItemStatus.ACTIVE) {
                    MessageUtil.send(
                        sender,
                        "<red>出品 <b>#$itemId</b> は現在 <b>${targetItem.status}</b> です。(ACTIVE の出品のみテスト可能です)</red>",
                        prefix = prefix,
                    )
                    future.complete(
                        StressTestResult(itemId, threadCount, 0, 0, 1, 0L, null),
                    )
                    return@task
                }

                if (targetItem.expiresAt <= System.currentTimeMillis()) {
                    MessageUtil.send(
                        sender,
                        "<red>出品 <b>#$itemId</b> は既に有効期限が切れています。</red>",
                        prefix = prefix,
                    )
                    future.complete(
                        StressTestResult(itemId, threadCount, 0, 0, 1, 0L, null),
                    )
                    return@task
                }

                MessageUtil.send(
                    sender,
                    "<yellow>出品 <b>#$itemId</b> に対して <b>$threadCount</b> スレッドでの並行購入ストレステストを開始します...</yellow>",
                    prefix = prefix,
                )
                logger.info("[FoliaMarket] Starting concurrent stress test on item #$itemId with $threadCount threads...")

                // 2. Perform synchronized concurrent execution
                val result = executeTest(itemId, threadCount)

                // 3. Render report to sender and console
                sendReport(sender, result)
                logReport(result)

                future.complete(result)
            } catch (e: Exception) {
                logger.severe("[FoliaMarket] Stress test failed with unhandled exception: ${e.message}")
                MessageUtil.send(
                    sender,
                    "<red>ストレステスト中に予期せぬエラーが発生しました: ${e.message}</red>",
                    prefix = prefix,
                )
                future.completeExceptionally(e)
            }
        }

        if (scheduler != null) {
            scheduler.runAsync(task)
        } else {
            CompletableFuture.runAsync(task)
        }

        return future
    }

    /**
     * Synchronously executes concurrent purchase attempts across multiple synchronized threads.
     * Accessible for direct unit testing.
     */
    fun executeTest(
        itemId: Long,
        threadCount: Int,
    ): StressTestResult {
        val readyGate = CountDownLatch(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)

        val successCount = AtomicInteger(0)
        val alreadySoldCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val successfulBuyers = ConcurrentLinkedQueue<String>()

        val threadFactory =
            ThreadFactory { r ->
                Thread(r, "FoliaMarket-StressWorker-${UUID.randomUUID().toString().take(8)}").apply {
                    isDaemon = true
                }
            }
        val executor = Executors.newFixedThreadPool(threadCount, threadFactory)

        try {
            for (i in 1..threadCount) {
                val dummyName = "Dummy-$i"
                val dummyUuid = UUID.randomUUID()

                executor.submit {
                    try {
                        // Signal ready and wait at start barrier
                        readyGate.countDown()
                        startGate.await()

                        // 1. Transaction pre-check: verify item status
                        val item = dao.getItemById(itemId)
                        if (item == null || item.status != MarketItemStatus.ACTIVE || item.expiresAt <= System.currentTimeMillis()) {
                            alreadySoldCount.incrementAndGet()
                            return@submit
                        }

                        // 2. Atomic Compare-And-Swap (CAS) update: ACTIVE -> SOLD
                        val casSuccess = dao.casUpdateStatus(itemId, MarketItemStatus.ACTIVE, MarketItemStatus.SOLD)
                        if (casSuccess) {
                            successCount.incrementAndGet()
                            successfulBuyers.add(dummyName)
                        } else {
                            alreadySoldCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        logger.warning("[FoliaMarket] Worker $dummyName encountered error: ${e.message}")
                        errorCount.incrementAndGet()
                    } finally {
                        doneGate.countDown()
                    }
                }
            }

            // Await readiness of all worker threads before firing
            readyGate.await(10, TimeUnit.SECONDS)

            // Release all threads simultaneously
            val startTime = System.currentTimeMillis()
            startGate.countDown()

            // Wait for all workers to complete
            doneGate.await(60, TimeUnit.SECONDS)
            val elapsedMs = System.currentTimeMillis() - startTime

            // If any worker succeeded, automatically roll back the item from SOLD to ACTIVE so items are never lost
            val totalSuccess = successCount.get()
            if (totalSuccess > 0) {
                dao.casUpdateStatus(itemId, MarketItemStatus.SOLD, MarketItemStatus.ACTIVE)
            }

            return StressTestResult(
                itemId = itemId,
                threadCount = threadCount,
                successCount = totalSuccess,
                alreadySoldCount = alreadySoldCount.get(),
                errorCount = errorCount.get(),
                elapsedMs = elapsedMs,
                winnerName = successfulBuyers.firstOrNull(),
            )
        } finally {
            executor.shutdown()
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (ie: InterruptedException) {
                executor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Formats and sends the stress test report using MiniMessage.
     */
    fun sendReport(
        sender: Audience,
        result: StressTestResult,
    ) {
        val line = "<dark_gray>--------------------------------------------------</dark_gray>"
        MessageUtil.send(sender, line)
        MessageUtil.send(
            sender,
            "<gradient:#00b4db:#0083b0><b>      ★ FoliaMarket 並行ストレステスト結果 ★</b></gradient>",
        )
        MessageUtil.send(sender, "<gray>対象出品ID: <white>#${result.itemId}</white></gray>")
        MessageUtil.send(sender, "<gray>同時実行スレッド数: <aqua>${result.threadCount} threads</aqua></gray>")
        MessageUtil.send(sender, "<gray>全処理所要時間: <yellow>${result.elapsedMs} ms</yellow></gray>")
        MessageUtil.send(sender, line)
        MessageUtil.send(sender, "<gray>購入成功数: <green><b>${result.successCount}</b></green></gray>")
        MessageUtil.send(
            sender,
            "<gray>売り切れ検知 (正常ロールバック): <yellow>${result.alreadySoldCount}</yellow></gray>",
        )
        MessageUtil.send(sender, "<gray>エラー・例外数: <red>${result.errorCount}</red></gray>")
        MessageUtil.send(sender, line)

        val verdictMessage =
            when {
                result.isPassed -> {
                    "<green><b>[PASSED]</b> 排他制御成功 (アイテム増殖ゼロ / 勝者: <white>${result.winnerName ?: "不明"}</white>)</green>"
                }

                result.isDupeDetected -> {
                    "<red><b>[CRITICAL ERROR]</b> DUPE DETECTED! 増殖バグ検知 (<white>${result.successCount}</white> 件の同時購入が成立)</red>"
                }

                else -> {
                    "<gold><b>[FAILED]</b> 購入成功者なし (商品が既に売り切れていたか無効です)</gold>"
                }
            }

        MessageUtil.send(sender, "<gray>総合判定: </gray>$verdictMessage")
        if (result.successCount > 0) {
            MessageUtil.send(
                sender,
                "<gray>✔ テスト完了後、アイテム <b>#${result.itemId}</b> を自動ロールバックし、出品中(ACTIVE)に安全に復元しました。</gray>",
            )
        }
        MessageUtil.send(sender, line)
    }

    private fun logReport(result: StressTestResult) {
        val verdict =
            when {
                result.isPassed -> "PASSED (No Dupe, Zero Race Condition)"
                result.isDupeDetected -> "CRITICAL_ERROR (DUPE DETECTED: ${result.successCount} wins)"
                else -> "FAILED (0 wins)"
            }
        logger.info(
            "[FoliaMarket] [STRESS TEST] Finished for item #${result.itemId}: " +
                "threads=${result.threadCount}, success=${result.successCount}, " +
                "rollbacks=${result.alreadySoldCount}, errors=${result.errorCount}, " +
                "elapsed=${result.elapsedMs}ms, verdict=$verdict, winner=${result.winnerName ?: "none"}",
        )
        if (result.successCount > 0) {
            logger.info("[FoliaMarket] [STRESS TEST] Rolled back item #${result.itemId} to ACTIVE status.")
        }
    }
}
