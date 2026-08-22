package ai.lightspeed.tipsy.shell.pages.screen.recommendation

import ai.lightspeed.tipsy.shell.auth.Generations
import ai.lightspeed.tipsy.shell.network.ApiException
import ai.lightspeed.tipsy.shell.pages.screen.ScreenAttribution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Screen 分享推荐反馈的可靠 reporter。
 *
 * ## 顺序保证
 *
 * [trackShare] 先用同步持久化确认事件已进入 Native 私有队列，随后才启动异步上传。
 * `SharedPreferences.commit() == false`、存储异常或 owner/auth 已变化都会返回 false，
 * 且绝不会上传一条未落盘事件。
 *
 * ## 账号边界
 *
 * 队列按 API host + owner 隔离。每轮发送前捕获 [Generations.auth]，请求返回后，
 * 删除已上传事件之前再次核对 owner 与 auth generation；换号会 bump generation，
 * 旧队列因而保留。正常的同账号 token refresh 不改变事件所有权，成功响应仍可确认。
 */
class ScreenRecommendationShareReporter(
    private val apiBaseUrl: String,
    private val storage: ScreenRecommendationQueueStorage,
    private val batchSource: ScreenRecommendationBatchSource,
    private val tokenProvider: ScreenRecommendationTokenProvider,
    private val ownerUserIdProvider: () -> String?,
    private val generations: Generations,
    private val coroutineScope: CoroutineScope,
    private val diagnostic: ScreenRecommendationDiagnostic,
    /** null=未知（允许尝试），false=只保队列，等待 NetworkCallback 恢复边沿。 */
    private val connectivityProvider: () -> Boolean? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val eventIdProvider: () -> UUID = UUID::randomUUID,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private val flushJobs = mutableMapOf<String, Job>()
    private val retryJobs = mutableMapOf<String, Job>()

    /**
     * 构造并可靠入队一条 share 反馈，随后 fire-and-forget 上传。
     *
     * @return true 仅表示事件已存在于持久化队列（同 event id 已入队也算 true）；
     * false 表示 owner/归因失配、事件无效或持久化失败。
     */
    fun trackShare(
        attribution: ScreenAttribution?,
        channel: ScreenRecommendationShareChannel,
    ): Boolean {
        if (attribution == null) return false
        val ownerUserId = currentOwnerUserId()
        if (ownerUserId.isEmpty() || ownerUserId != attribution.ownerUserId) return false
        val authGeneration = generations.auth
        val event = ScreenRecommendationShareEvent.create(
            attribution = attribution,
            channel = channel,
            eventId = eventIdProvider(),
            eventTimeMs = clock(),
        ) ?: return false
        val storageScope = ScreenRecommendationStorageScope.create(apiBaseUrl, ownerUserId)

        val didPersist = synchronized(lock) {
            if (!isCurrentScope(ownerUserId, authGeneration)) return@synchronized false
            val loaded = loadQueueLocked(storageScope, nowMs = clock())
            if (loaded == null) return@synchronized false
            if (loaded.any { it.eventId == event.eventId }) return@synchronized true

            val pruned = ScreenRecommendationShareQueueCodec.prune(
                events = loaded + event,
                nowMs = clock(),
            )
            if (!persistQueueLocked(storageScope, pruned.events, operation = "enqueue")) {
                return@synchronized false
            }
            reportPruned(pruned)
            true
        }

        if (didPersist) requestFlush(ownerUserId)
        return didPersist
    }

    /** Application 前台、网络恢复或冷启动时调用；同一个 host+owner 只会有一轮上传。 */
    fun flushCurrentOwner(): Job? {
        val ownerUserId = currentOwnerUserId().takeIf { it.isNotEmpty() } ?: return null
        return requestFlush(ownerUserId)
    }

    /**
     * Application 在 login/logout/换号已 bump auth generation 并发布新 owner 后调用。
     * 旧 owner 队列不删除；新 owner（或同 owner 的新 token）清掉旧退避并立即重试。
     */
    fun onAuthChanged(): Job? {
        val retryJobsToCancel = synchronized(lock) {
            retryJobs.values.toList().also { retryJobs.clear() }
        }
        retryJobsToCancel.forEach { it.cancel() }

        val ownerUserId = currentOwnerUserId().takeIf { it.isNotEmpty() } ?: return null
        val authGeneration = generations.auth
        val storageScope = ScreenRecommendationStorageScope.create(apiBaseUrl, ownerUserId)
        val priorFlush = synchronized(lock) {
            if (isCurrentScope(ownerUserId, authGeneration)) {
                removeRetryStateLocked(storageScope, operation = "auth_changed")
            }
            flushJobs[storageScope.identity]
        }
        return coroutineScope.launch {
            // auth session 已换代，旧请求即便成功也不能再确认旧队列；主动取消可让
            // ApiClient 立刻 Call.cancel()，不必让新会话等待网络 timeout。
            priorFlush?.cancel()
            priorFlush?.join()
            if (isCurrentScope(ownerUserId, authGeneration)) {
                requestFlush(ownerUserId)?.join()
            }
        }
    }

    private fun requestFlush(ownerUserId: String): Job? {
        if (isKnownOffline()) return null
        val storageScope = ScreenRecommendationStorageScope.create(apiBaseUrl, ownerUserId)
        val created: Job
        synchronized(lock) {
            flushJobs[storageScope.identity]?.takeIf { it.isActive }?.let { return it }
            created = coroutineScope.launch(start = CoroutineStart.LAZY) {
                flushScope(storageScope)
            }
            flushJobs[storageScope.identity] = created
            created.invokeOnCompletion {
                synchronized(lock) {
                    if (flushJobs[storageScope.identity] === created) {
                        flushJobs.remove(storageScope.identity)
                    }
                }
            }
        }
        created.start()
        return created
    }

    private suspend fun flushScope(storageScope: ScreenRecommendationStorageScope) {
        val ownerUserId = storageScope.ownerUserId
        val authGeneration = generations.auth
        if (!isCurrentScope(ownerUserId, authGeneration) || isKnownOffline()) return

        try {
            val persistedRetry = synchronized(lock) {
                if (!isCurrentScope(ownerUserId, authGeneration)) return
                loadRetryStateLocked(storageScope)
            }
            if (persistedRetry != null && persistedRetry.nextRetryAtMs > clock()) {
                scheduleRetry(storageScope, persistedRetry.nextRetryAtMs - clock())
                return
            }

            while (true) {
                if (isKnownOffline()) return
                val batch = synchronized(lock) {
                    if (!isCurrentScope(ownerUserId, authGeneration)) return
                    loadQueueLocked(storageScope, nowMs = clock())
                        ?.take(ScreenRecommendationShareQueueCodec.MAX_BATCH_SIZE)
                } ?: throw ScreenRecommendationUploadUnavailable("read_queue")
                if (batch.isEmpty()) {
                    synchronized(lock) {
                        if (isCurrentScope(ownerUserId, authGeneration)) {
                            removeRetryStateLocked(storageScope, operation = "flush_complete")
                        }
                    }
                    return
                }

                val frozenToken = tokenProvider.getValidToken()
                    ?: throw ScreenRecommendationUploadUnavailable("missing_auth_token")
                if (!isCurrentScope(ownerUserId, authGeneration)) return

                if (
                    !uploadBatchWithInvalidEventIsolation(
                        storageScope = storageScope,
                        batch = batch,
                        frozenToken = frozenToken,
                        authGeneration = authGeneration,
                    )
                ) {
                    return
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // NetworkCallback 已确认离线时只保留落盘队列，不能把离线窗口计成一次
            // 上传失败并放大退避；false→true 边沿会重新唤醒 flush。
            if (isCurrentScope(ownerUserId, authGeneration) && !isKnownOffline()) {
                recordFailure(storageScope, authGeneration, error)
            }
        }
    }

    /**
     * 对齐 RN `uploadBatchWithInvalidEventIsolation`：服务端 code=2 表示 payload 中
     * 存在永久无效事件。整批直接退避会让 FIFO 后续有效事件一直被坏事件堵住，
     * 因此二分到单条；成功子批立即从持久化队列确认删除，单条坏事件也持久化删除
     * 并留下不含 token/完整 payload 的诊断。其它错误仍交给外层统一退避。
     *
     * @return false 表示 await 期间 owner/auth session 已变化。此时不再触碰旧队列，
     * 已成功但尚未确认的请求依赖 event_id 在下一次同账号登录时安全重传。
     */
    private suspend fun uploadBatchWithInvalidEventIsolation(
        storageScope: ScreenRecommendationStorageScope,
        batch: List<ScreenRecommendationShareEvent>,
        frozenToken: String,
        authGeneration: Long,
    ): Boolean {
        if (batch.isEmpty()) return true
        if (!isCurrentScope(storageScope.ownerUserId, authGeneration)) return false

        try {
            batchSource.postBatch(
                jsonBody = ScreenRecommendationShareEvent.batchBody(batch),
                frozenToken = frozenToken,
            )
        } catch (error: ApiException.Business) {
            if (error.code != INVALID_PARAMETER_CODE) throw error
            if (batch.size > 1) {
                val midpoint = (batch.size + 1) / 2
                return uploadBatchWithInvalidEventIsolation(
                    storageScope = storageScope,
                    batch = batch.subList(0, midpoint),
                    frozenToken = frozenToken,
                    authGeneration = authGeneration,
                ) && uploadBatchWithInvalidEventIsolation(
                    storageScope = storageScope,
                    batch = batch.subList(midpoint, batch.size),
                    frozenToken = frozenToken,
                    authGeneration = authGeneration,
                )
            }

            if (!isCurrentScope(storageScope.ownerUserId, authGeneration)) return false
            val event = batch.single()
            val removed = synchronized(lock) {
                if (!isCurrentScope(storageScope.ownerUserId, authGeneration)) return false
                removeUploadedEventsLocked(storageScope, setOf(event.eventId))
            }
            if (!removed) {
                throw ScreenRecommendationUploadUnavailable("persist_invalid_event")
            }
            recordDiagnostic(
                EVENT_DROPPED,
                mapOf(
                    "error_name" to error.javaClass.simpleName,
                    "error_code" to error.code,
                    "event_id" to event.eventId,
                    "event_type" to EVENT_TYPE_SHARE,
                ),
            )
            return true
        }

        // 请求可能成功，但 owner/auth session 已在 await 期间变化。此时不能删旧队列；
        // event_id 让下一次重传可由服务端去重。
        if (!isCurrentScope(storageScope.ownerUserId, authGeneration)) return false
        val removed = synchronized(lock) {
            if (!isCurrentScope(storageScope.ownerUserId, authGeneration)) return false
            removeUploadedEventsLocked(
                storageScope = storageScope,
                eventIds = batch.mapTo(HashSet()) { it.eventId },
            )
        }
        if (!removed) throw ScreenRecommendationUploadUnavailable("persist_uploaded_batch")
        return true
    }

    private fun removeUploadedEventsLocked(
        storageScope: ScreenRecommendationStorageScope,
        eventIds: Set<String>,
    ): Boolean {
        val current = loadQueueLocked(storageScope, nowMs = clock()) ?: return false
        val remaining = current.filterNot { it.eventId in eventIds }
        return persistQueueLocked(storageScope, remaining, operation = "remove_uploaded")
    }

    /**
     * 读取时同时修复 invalid/duplicate/expired/overflow。语法损坏或顶层非数组时
     * 整队丢弃并记录；持久化修复失败则返回 null，调用方不能假装拿到了可靠队列。
     */
    private fun loadQueueLocked(
        storageScope: ScreenRecommendationStorageScope,
        nowMs: Long,
    ): List<ScreenRecommendationShareEvent>? {
        val read = readStorageLocked(storageScope.queueKey, "read_queue")
        if (!read.succeeded) return null
        val raw = read.value ?: return emptyList()
        val parsed = ScreenRecommendationShareQueueCodec.parse(raw)
        if (parsed.corrupted) {
            val removed = removeStorageLocked(storageScope.queueKey, "discard_corrupted_queue")
            recordDiagnostic(
                EVENT_QUEUE_PRUNED,
                mapOf(
                    "corrupted" to true,
                    "invalid_count" to parsed.droppedInvalidCount,
                    "duplicate_count" to parsed.droppedDuplicateCount,
                ),
            )
            return if (removed) emptyList() else null
        }

        val pruned = ScreenRecommendationShareQueueCodec.prune(parsed.events, nowMs)
        val requiresRepair =
            parsed.droppedInvalidCount > 0 ||
                parsed.droppedDuplicateCount > 0 ||
                pruned.droppedExpiredCount > 0 ||
                pruned.droppedOverflowCount > 0
        if (requiresRepair) {
            if (!persistQueueLocked(storageScope, pruned.events, operation = "repair_queue")) {
                return null
            }
            recordDiagnostic(
                EVENT_QUEUE_PRUNED,
                mapOf(
                    "corrupted" to false,
                    "invalid_count" to parsed.droppedInvalidCount,
                    "duplicate_count" to parsed.droppedDuplicateCount,
                    "expired_count" to pruned.droppedExpiredCount,
                    "overflow_count" to pruned.droppedOverflowCount,
                ),
            )
        }
        return pruned.events
    }

    private fun persistQueueLocked(
        storageScope: ScreenRecommendationStorageScope,
        events: List<ScreenRecommendationShareEvent>,
        operation: String,
    ): Boolean {
        val value = ScreenRecommendationShareQueueCodec.serialize(events)
        return writeStorageLocked(
            key = storageScope.queueKey,
            value = value,
            operation = operation,
            queueLength = events.size,
        )
    }

    private fun loadRetryStateLocked(storageScope: ScreenRecommendationStorageScope): ScreenRecommendationRetryState? {
        val read = readStorageLocked(storageScope.retryKey, "read_retry")
        if (!read.succeeded) return null
        val raw = read.value ?: return null
        val parsed = ScreenRecommendationRetryState.parse(raw)
        if (parsed == null) {
            removeStorageLocked(storageScope.retryKey, "discard_corrupted_retry")
            recordDiagnostic(EVENT_RETRY_STATE_PRUNED, mapOf("corrupted" to true))
        }
        return parsed
    }

    private fun removeRetryStateLocked(
        storageScope: ScreenRecommendationStorageScope,
        operation: String,
    ) {
        removeStorageLocked(storageScope.retryKey, operation)
    }

    private fun recordFailure(
        storageScope: ScreenRecommendationStorageScope,
        authGeneration: Long,
        error: Throwable,
    ) {
        val retryState = synchronized(lock) {
            if (!isCurrentScope(storageScope.ownerUserId, authGeneration)) return
            val previous = loadRetryStateLocked(storageScope)
            val attempt = ((previous?.attempt ?: 0) + 1)
                .coerceAtMost(ScreenRecommendationRetryPolicy.MAX_ATTEMPT)
            val state = ScreenRecommendationRetryState(
                attempt = attempt,
                nextRetryAtMs = clock() + ScreenRecommendationRetryPolicy.delayMs(attempt),
            )
            writeStorageLocked(
                key = storageScope.retryKey,
                value = state.toJson(),
                operation = "persist_retry",
                queueLength = -1,
            )
            state
        }
        recordDiagnostic(
            EVENT_FLUSH_FAILED,
            mapOf(
                "error_name" to error.javaClass.simpleName,
                "attempt" to retryState.attempt,
            ),
        )
        scheduleRetry(storageScope, (retryState.nextRetryAtMs - clock()).coerceAtLeast(0L))
    }

    private fun scheduleRetry(
        storageScope: ScreenRecommendationStorageScope,
        delayMs: Long,
    ): Job {
        val created: Job
        synchronized(lock) {
            retryJobs[storageScope.identity]?.takeIf { it.isActive }?.let { return it }
            created = coroutineScope.launch(start = CoroutineStart.LAZY) {
                retryDelay(delayMs.coerceAtLeast(0L))
                // 若失败发生时本轮 flush 还没退出，先等它释放 single-flight 槽。
                synchronized(lock) { flushJobs[storageScope.identity] }?.join()
                // 在启动下一轮前先释放 retry single-flight；否则下一轮若同步失败，
                // 会看到本 Job 仍 active 而漏排下一档退避。
                synchronized(lock) { retryJobs.remove(storageScope.identity) }
                if (currentOwnerUserId() == storageScope.ownerUserId) {
                    requestFlush(storageScope.ownerUserId)
                }
            }
            retryJobs[storageScope.identity] = created
            created.invokeOnCompletion {
                synchronized(lock) {
                    if (retryJobs[storageScope.identity] === created) {
                        retryJobs.remove(storageScope.identity)
                    }
                }
            }
        }
        created.start()
        return created
    }

    private fun readStorageLocked(key: String, operation: String): StorageRead = try {
        StorageRead(succeeded = true, value = storage.read(key))
    } catch (error: Throwable) {
        reportPersistFailure(operation, error, -1)
        StorageRead(succeeded = false, value = null)
    }

    private fun writeStorageLocked(
        key: String,
        value: String,
        operation: String,
        queueLength: Int,
    ): Boolean = try {
        storage.write(key, value).also { succeeded ->
            if (!succeeded) reportPersistFailure(operation, null, queueLength)
        }
    } catch (error: Throwable) {
        reportPersistFailure(operation, error, queueLength)
        false
    }

    private fun removeStorageLocked(key: String, operation: String): Boolean = try {
        storage.remove(key).also { succeeded ->
            if (!succeeded) reportPersistFailure(operation, null, -1)
        }
    } catch (error: Throwable) {
        reportPersistFailure(operation, error, -1)
        false
    }

    private fun reportPersistFailure(operation: String, error: Throwable?, queueLength: Int) {
        val fields = mutableMapOf<String, Any>("operation" to operation)
        if (queueLength >= 0) fields["queue_length"] = queueLength
        if (error != null) fields["error_name"] = error.javaClass.simpleName
        recordDiagnostic(EVENT_PERSIST_FAILED, fields)
    }

    private fun reportPruned(pruned: ScreenRecommendationPrunedQueue) {
        if (pruned.droppedExpiredCount == 0 && pruned.droppedOverflowCount == 0) return
        recordDiagnostic(
            EVENT_QUEUE_PRUNED,
            mapOf(
                "corrupted" to false,
                "expired_count" to pruned.droppedExpiredCount,
                "overflow_count" to pruned.droppedOverflowCount,
            ),
        )
    }

    private fun recordDiagnostic(eventCode: String, fields: Map<String, Any>) {
        runCatching { diagnostic.record(eventCode, fields) }
    }

    private fun currentOwnerUserId(): String = ownerUserIdProvider()?.trim().orEmpty()

    private fun isKnownOffline(): Boolean =
        runCatching(connectivityProvider).getOrNull() == false

    private fun isCurrentScope(ownerUserId: String, authGeneration: Long): Boolean =
        currentOwnerUserId() == ownerUserId && generations.auth == authGeneration

    private class ScreenRecommendationUploadUnavailable(reason: String) : Exception(reason)

    private data class StorageRead(val succeeded: Boolean, val value: String?)

    private companion object {
        const val EVENT_PERSIST_FAILED = "screen_recommend_tracking_persist_failed"
        const val EVENT_QUEUE_PRUNED = "screen_recommend_tracking_queue_pruned"
        const val EVENT_RETRY_STATE_PRUNED = "screen_recommend_tracking_retry_state_pruned"
        const val EVENT_FLUSH_FAILED = "screen_recommend_tracking_flush_failed"
        const val EVENT_DROPPED = "screen_recommend_tracking_event_dropped"
        const val EVENT_TYPE_SHARE = "share"
        const val INVALID_PARAMETER_CODE = 2
    }
}
