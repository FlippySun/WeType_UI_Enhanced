package com.xposed.wetypehook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import com.xposed.wetypehook.wetype.settings.WeTypeBackgroundImageStore
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"

/*
 * 2026-08-25
 * Change type: add
 * What: 由真实 WeType Activity 启动系统图片选择器，回传一次性 Uri，并在宿主销毁时清理寄生对话框。
 * Why: 寄生设置的 ComponentDialog 不保证提供 ActivityResultRegistryOwner，直接复用宿主 Activity 可确保临时读取授权属于 WeType UID。
 * Params & return: 输入宿主 Activity 与一次性回调，返回选择器是否成功启动或该结果是否被模块识别。
 * Impact scope: 寄生设置页选图、Activity.dispatchActivityResult hook、Activity 生命周期与热重载清理。
 * Risk: 仅占用一个由 ASCII "WT" 派生的 requestCode；同一进程只保留一个设置对话框，原 Activity 结果链仍会继续执行。
 */
private const val BACKGROUND_IMAGE_REQUEST_CODE = 0x5754
private val activeHostDialogs = WeakHashMap<Activity, ComponentDialog>()
private val backgroundImageResultCallbacks = WeakHashMap<Activity, (Uri?) -> Unit>()
private val backgroundOperationsInProgress = WeakHashMap<Activity, Boolean>()
private val moduleResourcesCache = HashMap<String, Resources>()
private var lifecycleApplication: Application? = null

private val hostActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        WeTypeHostLauncher.onHostActivityDestroyed(activity)
    }
}

object WeTypeHostLauncher {
    fun show(activity: Activity) {
        ensureActivityLifecycleCleanup(activity)
        val existingEntry = synchronized(activeHostDialogs) {
            activeHostDialogs.entries.firstOrNull { it.value.isShowing }
        }
        if (existingEntry != null) {
            if (existingEntry.key === activity) return
            val operationInProgress = synchronized(backgroundOperationsInProgress) {
                backgroundOperationsInProgress[existingEntry.key] == true
            }
            if (operationInProgress) return
            cleanupHostActivity(existingEntry.key, dismissDialog = true)
        }
        WeTypeBackgroundImageStore.discardStagedImageIfIdle(activity)
        WeTypeSettings.bindModuleBridgePendingIntent(
            ModuleBridgeContract.settingsBridgePendingIntent(activity.intent)
        )

        val moduleContext = runCatching {
            activity.createPackageContext(
                MODULE_PACKAGE_NAME,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
        }.getOrElse {
            Log.e("Failed:Create module package context for WeType host dialog")
            Log.i(it)
            createEmbeddedModuleContext(activity)
                ?: return
        }

        val dialog = ComponentDialog(
            ModuleHostContext(activity, moduleContext),
            R.style.Theme_WeTypeHook_HostDialog
        ).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnDismissListener {
                activeHostDialogs.remove(activity)
                cancelBackgroundImageRequest(activity)
                synchronized(backgroundOperationsInProgress) {
                    backgroundOperationsInProgress.remove(activity)
                }
                WeTypeBackgroundImageStore.discardStagedImageIfIdle(activity)
                WeTypeSettings.bindModuleBridgePendingIntent(null)
            }
        }
        activeHostDialogs[activity] = dialog
        val windowBackgroundColor = resolveWindowBackgroundColor(dialog.context)

        val composeView = ComposeView(dialog.context).apply {
            setBackgroundColor(windowBackgroundColor)
            setContent {
                WeTypeSettingsApp(
                    settingsContext = activity
                )
            }
        }
        dialog.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.show()
        dialog.window?.apply {
            decorView.setPadding(0, 0, 0, 0)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            statusBarColor = windowBackgroundColor
            navigationBarColor = windowBackgroundColor
            setBackgroundDrawable(ColorDrawable(windowBackgroundColor))
        }
    }

    fun requestBackgroundImage(activity: Activity, onResult: (Uri?) -> Unit): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        synchronized(backgroundImageResultCallbacks) {
            backgroundImageResultCallbacks[activity] = onResult
        }
        val launched = runCatching {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, BACKGROUND_IMAGE_REQUEST_CODE)
        }.onFailure { error ->
            Log.e("Failed:Open WeType custom background picker")
            Log.i(error)
        }.isSuccess
        if (!launched) cancelBackgroundImageRequest(activity)
        return launched
    }

    fun dispatchBackgroundImageResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        if (requestCode != BACKGROUND_IMAGE_REQUEST_CODE) return false
        val callback = synchronized(backgroundImageResultCallbacks) {
            backgroundImageResultCallbacks.remove(activity)
        } ?: return false
        runCatching {
            callback(data?.data.takeIf { resultCode == Activity.RESULT_OK })
        }.onFailure { error ->
            Log.e("Failed:Deliver WeType custom background picker result")
            Log.i(error)
        }
        return true
    }

    fun setBackgroundOperationInProgress(activity: Activity, inProgress: Boolean) {
        synchronized(backgroundOperationsInProgress) {
            if (inProgress) {
                backgroundOperationsInProgress[activity] = true
            } else {
                backgroundOperationsInProgress.remove(activity)
            }
        }
        activeHostDialogs[activity]?.setCancelable(!inProgress)
    }

    fun isHostDialogShowing(activity: Activity): Boolean =
        activeHostDialogs[activity]?.isShowing == true

    fun cancelBackgroundImageRequest(activity: Activity) {
        synchronized(backgroundImageResultCallbacks) {
            backgroundImageResultCallbacks.remove(activity)
        }
    }

    fun onHostActivityDestroyed(activity: Activity) {
        val tracked = synchronized(activeHostDialogs) {
            activeHostDialogs.containsKey(activity)
        } || synchronized(backgroundImageResultCallbacks) {
            backgroundImageResultCallbacks.containsKey(activity)
        } || synchronized(backgroundOperationsInProgress) {
            backgroundOperationsInProgress.containsKey(activity)
        }
        if (!tracked) return
        cleanupHostActivity(activity, dismissDialog = true)
    }

    fun prepareForHotReload(): Boolean {
        val cleaned = runOnMainThreadBlocking {
            val dialogEntries = synchronized(activeHostDialogs) {
                activeHostDialogs.entries.toList().also { activeHostDialogs.clear() }
            }
            dialogEntries.forEach { (activity, dialog) ->
                dialog.setOnDismissListener(null)
                if (dialog.isShowing) dialog.dismiss()
                WeTypeBackgroundImageStore.discardStagedImageIfIdle(activity)
            }
            synchronized(backgroundImageResultCallbacks) {
                backgroundImageResultCallbacks.clear()
            }
            synchronized(backgroundOperationsInProgress) {
                backgroundOperationsInProgress.clear()
            }
            lifecycleApplication?.unregisterActivityLifecycleCallbacks(
                hostActivityLifecycleCallbacks
            )
            lifecycleApplication = null
        }
        if (cleaned) {
            WeTypeSettings.bindModuleBridgePendingIntent(null)
            synchronized(moduleResourcesCache) {
                moduleResourcesCache.clear()
            }
        }
        return cleaned
    }

    private fun ensureActivityLifecycleCleanup(activity: Activity) {
        val application = activity.application
        if (lifecycleApplication === application) return
        lifecycleApplication?.unregisterActivityLifecycleCallbacks(hostActivityLifecycleCallbacks)
        application.registerActivityLifecycleCallbacks(hostActivityLifecycleCallbacks)
        lifecycleApplication = application
    }

    private fun cleanupHostActivity(activity: Activity, dismissDialog: Boolean) {
        val dialog = synchronized(activeHostDialogs) {
            activeHostDialogs.remove(activity)
        }
        dialog?.setOnDismissListener(null)
        if (dismissDialog && dialog?.isShowing == true) dialog.dismiss()
        cancelBackgroundImageRequest(activity)
        synchronized(backgroundOperationsInProgress) {
            backgroundOperationsInProgress.remove(activity)
        }
        WeTypeBackgroundImageStore.discardStagedImageIfIdle(activity)
        if (activeHostDialogs.isEmpty()) WeTypeSettings.bindModuleBridgePendingIntent(null)
    }

    private fun createEmbeddedModuleContext(activity: Activity): Context? {
        val moduleApkPath = ModuleRuntime.resolveModuleApkPath()
        if (moduleApkPath == null) {
            Log.e("Failed:Resolve module apk path for embedded WeType host dialog")
            return null
        }
        val moduleResources = runCatching {
            synchronized(moduleResourcesCache) {
                moduleResourcesCache.getOrPut(moduleApkPath) {
                    val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val addAssetPath = AssetManager::class.java.getMethod(
                        "addAssetPath",
                        String::class.java
                    )
                    check(addAssetPath.invoke(assetManager, moduleApkPath) as Int != 0) {
                        "Failed to add embedded module asset path: $moduleApkPath"
                    }
                    Resources(
                        assetManager,
                        activity.resources.displayMetrics,
                        activity.resources.configuration
                    )
                }
            }
        }.getOrElse {
            Log.e("Failed:Create embedded module resources for WeType host dialog")
            Log.i(it)
            return null
        }
        return EmbeddedModuleContext(activity, moduleResources)
    }
}

private fun runOnMainThreadBlocking(block: () -> Unit): Boolean {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        return runCatching(block).onFailure {
            Log.e("Failed:Cleanup WeType host dialog for hot reload")
            Log.i(it)
        }.isSuccess
    }

    val completed = CountDownLatch(1)
    var failure: Throwable? = null
    if (!Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                completed.countDown()
            }
        }
    ) {
        return false
    }
    val finished = runCatching { completed.await(2, TimeUnit.SECONDS) }.getOrDefault(false)
    failure?.let {
        Log.e("Failed:Cleanup WeType host dialog for hot reload")
        Log.i(it)
    }
    return finished && failure == null
}

private fun resolveWindowBackgroundColor(context: Context): Int {
    val isDarkMode =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    return if (isDarkMode) Color.BLACK else Color.parseColor("#F7F7F7")
}

private class ModuleHostContext(
    baseContext: Context,
    private val moduleContext: Context
) : ContextThemeWrapper(baseContext, 0) {
    private val hostApplicationContext = baseContext.applicationContext ?: baseContext
    private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
        moduleContext.resources.newTheme().apply {
            setTo(moduleContext.theme)
        }
    }

    override fun getApplicationContext(): Context {
        return hostApplicationContext
    }

    override fun getAssets(): AssetManager {
        return moduleContext.assets
    }

    override fun getResources(): Resources {
        return moduleContext.resources
    }

    override fun getTheme(): Resources.Theme {
        return moduleTheme
    }

    override fun setTheme(resid: Int) {
        moduleTheme.applyStyle(resid, true)
    }

    override fun getPackageName(): String {
        return moduleContext.packageName
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return moduleContext.applicationInfo
    }

    override fun getClassLoader(): ClassLoader {
        return moduleContext.classLoader
    }
}

private class EmbeddedModuleContext(
    baseContext: Context,
    private val moduleResources: Resources
) : ContextThemeWrapper(baseContext, 0) {
    private val hostBaseContext = baseContext
    private val hostApplicationContext = baseContext.applicationContext ?: baseContext
    private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
        moduleResources.newTheme().apply {
            setTo(baseContext.theme)
            applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar, true)
        }
    }

    override fun getApplicationContext(): Context {
        return hostApplicationContext
    }

    override fun getAssets(): AssetManager {
        return moduleResources.assets
    }

    override fun getResources(): Resources {
        return moduleResources
    }

    override fun getTheme(): Resources.Theme {
        return moduleTheme
    }

    override fun setTheme(resid: Int) {
        moduleTheme.applyStyle(resid, true)
    }

    override fun getPackageName(): String {
        return MODULE_PACKAGE_NAME
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return hostBaseContext.applicationInfo
    }

    override fun getClassLoader(): ClassLoader {
        return WeTypeHostLauncher::class.java.classLoader ?: hostBaseContext.classLoader
    }
}
