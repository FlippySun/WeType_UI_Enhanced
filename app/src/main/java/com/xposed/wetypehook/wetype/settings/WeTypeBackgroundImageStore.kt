package com.xposed.wetypehook.wetype.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.xposed.wetypehook.xposed.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/*
 * 2026-08-25
 * Change type: add
 * What: 新增微信输入法私有背景图的导入、暂存、原子提交、删除与进程内解码缓存。
 * Why: 选图权限属于真实 WeType 宿主，固定写入其 noBackupFilesDir 可避免跨 UID URI、Binder 大图和外部存储权限问题。
 * Params & return: 输入宿主 Context 与临时 Uri，返回操作是否成功或可直接绘制的 Bitmap。
 * Impact scope: 设置页图片草稿、WeType 输入法背景 carrier、同 UID 多进程文件读取与热重载清理。
 * Risk: 损坏或超大图片会被限尺寸解码并安全回退；热重载会停止旧队列，但不会中断正在提交的原子写入。
 */
internal object WeTypeBackgroundImageStore {
    private const val STORED_IMAGE_FILE_NAME = "wetype_custom_background.webp"
    private const val STAGED_IMAGE_FILE_NAME = "wetype_custom_background.stage.webp"
    private const val SOURCE_IMAGE_FILE_NAME = "wetype_custom_background.source"

    // 32 MiB 限制系统选择器返回的实际压缩文件大小，避免异常 Provider 让输入法进程耗尽内存或磁盘。
    private const val MAX_SOURCE_IMAGE_BYTES = 32L * 1024L * 1024L

    // 2048px 足以覆盖常见高分辨率键盘区域，同时把软件 Bitmap 上限控制在约 16 MiB。
    private const val MAX_STORED_IMAGE_DIMENSION_PX = 2048

    // 设置页预览宽度远小于原图，512px 可避免在 Compose 中长期持有完整背景位图。
    private const val MAX_PREVIEW_IMAGE_DIMENSION_PX = 512

    // 90 是背景图清晰度与私有存储占用之间的保守折中，WebP 仍可保留透明通道。
    private const val STORED_IMAGE_QUALITY = 90

    private data class ImageStamp(
        val lastModified: Long,
        val length: Long
    )

    private val cacheLock = Any()
    private var cachedStamp: ImageStamp? = null
    private var cachedBitmap: Bitmap? = null

    // 所有草稿写入、提交与删除在同一队列执行，避免 AtomicFile 对同一路径并发写入时互相覆盖。
    // 保存计数独立加锁，使对话框销毁时不会删掉仍在等待设置 ACK 的图片草稿。
    private val fileTransactionExecutorLock = Any()
    private val fileTransactionExecutor = Executors.newSingleThreadExecutor()
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val saveTransactionLock = Any()
    private var pendingSaveTransactions = 0
    private var pendingFileTransactions = 0
    @Volatile
    private var hotReloading = false

    fun hasStoredImage(context: Context): Boolean = storedImageFile(context).isUsableImageFile()

    fun stageImageAsync(context: Context, uri: Uri, onComplete: (Bitmap?) -> Unit) {
        val appContext = hostContext(context)
        val scheduled = executeFileTransaction {
            val preview = runCatching {
                stageImage(appContext, uri)
            }.onFailure { error ->
                Log.i("Failed: Import WeType custom background")
                Log.i(error)
            }.getOrNull()
            postResult { onComplete(preview) }
        }
        if (!scheduled) {
            Log.i("Failed: Schedule WeType custom background import")
            postResult { onComplete(null) }
        }
    }

    fun beginSaveTransaction(): Boolean {
        return synchronized(fileTransactionExecutorLock) {
            if (hotReloading || fileTransactionExecutor.isShutdown) return@synchronized false
            synchronized(saveTransactionLock) {
                pendingSaveTransactions++
            }
            true
        }
    }

    fun persistDraftAsync(
        context: Context,
        settingsSaved: Boolean,
        imageDirty: Boolean,
        imageEnabled: Boolean,
        stagedImage: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        val appContext = hostContext(context)
        val scheduled = executeFileTransaction {
            val imageSaved = try {
                runCatching {
                    settingsSaved && when {
                        !imageDirty -> true
                        !imageEnabled -> {
                            deleteStagedImage(appContext)
                            removeStoredImage(appContext)
                        }
                        stagedImage -> commitStagedImage(appContext)
                        else -> true
                    }
                }.onFailure { error ->
                    Log.i("Failed: Persist WeType custom background draft")
                    Log.i(error)
                }.getOrDefault(false)
            } finally {
                finishSaveTransaction()
            }
            postResult { onComplete(imageSaved) }
        }
        if (!scheduled) {
            finishSaveTransaction()
            Log.i("Failed: Schedule WeType custom background save")
            postResult { onComplete(false) }
        }
    }

    fun discardStagedImage(context: Context) {
        val appContext = hostContext(context)
        if (!executeFileTransaction { deleteStagedImage(appContext) }) {
            Log.i("Failed: Schedule WeType custom background discard")
        }
    }

    fun discardStagedImageIfIdle(context: Context) {
        val appContext = hostContext(context)
        val scheduled = executeFileTransaction {
            val savePending = synchronized(saveTransactionLock) {
                pendingSaveTransactions > 0
            }
            if (!savePending) deleteStagedImage(appContext)
        }
        if (!scheduled) {
            Log.i("Failed: Schedule idle WeType background discard")
        }
    }

    fun prepareForHotReload(): Boolean {
        val executor = synchronized(fileTransactionExecutorLock) {
            val savePending = synchronized(saveTransactionLock) {
                pendingSaveTransactions > 0
            }
            if (savePending || pendingFileTransactions > 0) return false
            hotReloading = true
            callbackHandler.removeCallbacksAndMessages(null)
            fileTransactionExecutor.shutdown()
            fileTransactionExecutor
        }
        clearDecodedCache()
        // 两秒只用于等待当前小型私有文件事务收尾；超时会拒绝热重载，避免遗留旧 classloader 线程。
        return runCatching { executor.awaitTermination(2L, java.util.concurrent.TimeUnit.SECONDS) }
            .getOrDefault(false)
    }

    fun loadStoredPreview(context: Context): Bitmap? {
        return loadPreview(storedImageFile(context))
    }

    fun loadStagedPreview(context: Context): Bitmap? {
        return loadPreview(stagedImageFile(context))
    }

    private fun loadPreview(file: File): Bitmap? {
        if (!file.isUsableImageFile()) return null
        return decodeSource(
            source = ImageDecoder.createSource(file),
            maxDimension = MAX_PREVIEW_IMAGE_DIMENSION_PX
        )
    }

    fun loadStoredBitmap(context: Context): Bitmap? {
        val file = storedImageFile(context)
        val stamp = file.imageStampOrNull()
        synchronized(cacheLock) {
            if (stamp == cachedStamp) return cachedBitmap
            cachedStamp = stamp
            cachedBitmap = stamp?.let {
                decodeSource(
                    source = ImageDecoder.createSource(file),
                    maxDimension = MAX_STORED_IMAGE_DIMENSION_PX
                )
            }
            return cachedBitmap
        }
    }

    private fun stageImage(context: Context, uri: Uri): Bitmap? {
        val sourceFile = sourceImageFile(context)
        return try {
            if (!copySourceWithLimit(context, uri, sourceFile)) return null
            val bitmap = decodeSource(
                source = ImageDecoder.createSource(sourceFile),
                maxDimension = MAX_STORED_IMAGE_DIMENSION_PX
            ) ?: return null
            if (!writeBitmapAtomically(stagedImageFile(context), bitmap)) return null
            runCatching {
                bitmap.scaledDownTo(MAX_PREVIEW_IMAGE_DIMENSION_PX)
            }.onFailure { error ->
                deleteStagedImage(context)
                Log.i("Failed: Scale WeType custom background preview")
                Log.i(error)
            }.getOrNull()
        } finally {
            AtomicFile(sourceFile).delete()
        }
    }

    private fun commitStagedImage(context: Context): Boolean {
        val stagedFile = stagedImageFile(context)
        if (!stagedFile.isUsableImageFile()) return false

        val targetFile = storedImageFile(context)
        val atomicFile = AtomicFile(targetFile)
        var output: FileOutputStream? = null
        return try {
            val target = atomicFile.startWrite().also { output = it }
            stagedFile.inputStream().use { input -> input.copyTo(target) }
            atomicFile.finishWrite(target)
            output = null
            if (!stagedFile.hasSameContent(targetFile)) {
                Log.i("Failed: Verify committed WeType custom background image")
                return false
            }
            AtomicFile(stagedFile).delete()
            clearDecodedCache()
            true
        } catch (error: Throwable) {
            output?.let { stream -> runCatching { atomicFile.failWrite(stream) } }
            Log.i("Failed: Commit WeType custom background image")
            Log.i(error)
            false
        }
    }

    private fun deleteStagedImage(context: Context) {
        AtomicFile(stagedImageFile(context)).delete()
    }

    private fun removeStoredImage(context: Context): Boolean {
        AtomicFile(storedImageFile(context)).delete()
        clearDecodedCache()
        return !hasStoredImage(context)
    }

    private fun copySourceWithLimit(context: Context, uri: Uri, file: File): Boolean {
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("ContentResolver returned no input stream")
            var totalBytes = 0L
            input.buffered().use { source ->
                val target = atomicFile.startWrite().also { output = it }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_SOURCE_IMAGE_BYTES) {
                        throw IOException("Selected image exceeds the source byte limit")
                    }
                    target.write(buffer, 0, count)
                }
                atomicFile.finishWrite(target)
                output = null
            }
            (file.isUsableImageFile() && file.length() == totalBytes).also { verified ->
                if (!verified) Log.i("Failed: Verify WeType custom background source")
            }
        } catch (error: Throwable) {
            output?.let { stream -> runCatching { atomicFile.failWrite(stream) } }
            Log.i("Failed: Copy WeType custom background source")
            Log.i(error)
            false
        }
    }

    private fun writeBitmapAtomically(file: File, bitmap: Bitmap): Boolean {
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        return try {
            val target = atomicFile.startWrite().also { output = it }
            if (!bitmap.compress(
                    Bitmap.CompressFormat.WEBP_LOSSY,
                    STORED_IMAGE_QUALITY,
                    target
                )
            ) {
                throw IOException("Bitmap compression returned false")
            }
            atomicFile.finishWrite(target)
            output = null
            val verified = file.isUsableImageFile() && decodeSource(
                source = ImageDecoder.createSource(file),
                maxDimension = MAX_PREVIEW_IMAGE_DIMENSION_PX
            ) != null
            verified.also {
                if (!verified) Log.i("Failed: Verify staged WeType custom background image")
            }
        } catch (error: Throwable) {
            output?.let { stream -> runCatching { atomicFile.failWrite(stream) } }
            Log.i("Failed: Stage WeType custom background image")
            Log.i(error)
            false
        }
    }

    private fun decodeSource(source: ImageDecoder.Source, maxDimension: Int): Bitmap? =
        runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                check(width > 0 && height > 0) { "Invalid image dimensions" }
                val largestDimension = maxOf(width, height)
                if (largestDimension > maxDimension) {
                    val scale = maxDimension.toFloat() / largestDimension
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        }.onFailure { error ->
            Log.i("Failed: Decode WeType custom background image")
            Log.i(error)
        }.getOrNull()

    private fun Bitmap.scaledDownTo(maxDimension: Int): Bitmap {
        val largestDimension = maxOf(width, height)
        if (largestDimension <= maxDimension) return this
        val scale = maxDimension.toFloat() / largestDimension
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun storedImageFile(context: Context): File =
        File(hostContext(context).noBackupFilesDir, STORED_IMAGE_FILE_NAME)

    private fun stagedImageFile(context: Context): File =
        File(hostContext(context).noBackupFilesDir, STAGED_IMAGE_FILE_NAME)

    private fun sourceImageFile(context: Context): File =
        File(hostContext(context).noBackupFilesDir, SOURCE_IMAGE_FILE_NAME)

    private fun hostContext(context: Context): Context = context.applicationContext ?: context

    private fun File.isUsableImageFile(): Boolean = isFile && length() > 0L

    private fun File.imageStampOrNull(): ImageStamp? = takeIf { it.isUsableImageFile() }?.let {
        ImageStamp(lastModified = it.lastModified(), length = it.length())
    }

    private fun File.hasSameContent(other: File): Boolean {
        if (!isFile || !other.isFile || length() != other.length()) return false
        return inputStream().buffered().use { first ->
            other.inputStream().buffered().use { second ->
                val firstBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val secondBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var matches = true
                while (true) {
                    val firstCount = first.read(firstBuffer)
                    val secondCount = second.read(secondBuffer)
                    if (firstCount != secondCount) {
                        matches = false
                        break
                    }
                    if (firstCount < 0) break
                    for (index in 0 until firstCount) {
                        if (firstBuffer[index] != secondBuffer[index]) {
                            matches = false
                            break
                        }
                    }
                    if (!matches) break
                }
                matches
            }
        }
    }

    private fun finishSaveTransaction() {
        synchronized(saveTransactionLock) {
            pendingSaveTransactions = (pendingSaveTransactions - 1).coerceAtLeast(0)
        }
    }

    private fun executeFileTransaction(block: () -> Unit): Boolean {
        return synchronized(fileTransactionExecutorLock) {
            if (hotReloading || fileTransactionExecutor.isShutdown) return@synchronized false
            pendingFileTransactions++
            runCatching {
                fileTransactionExecutor.execute {
                    try {
                        block()
                    } finally {
                        synchronized(fileTransactionExecutorLock) {
                            pendingFileTransactions = (pendingFileTransactions - 1).coerceAtLeast(0)
                        }
                    }
                }
                true
            }.onFailure { error ->
                pendingFileTransactions = (pendingFileTransactions - 1).coerceAtLeast(0)
                Log.i("Failed: Queue WeType custom background transaction")
                Log.i(error)
            }.getOrDefault(false)
        }
    }

    private fun postResult(block: () -> Unit) {
        synchronized(fileTransactionExecutorLock) {
            if (hotReloading) return
            callbackHandler.post {
                runCatching(block).onFailure { error ->
                    Log.i("Failed: Deliver WeType custom background result")
                    Log.i(error)
                }
            }
        }
    }

    private fun clearDecodedCache() {
        synchronized(cacheLock) {
            cachedStamp = null
            cachedBitmap = null
        }
    }
}
