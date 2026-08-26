package com.xposed.wetypehook

import java.io.File

object ModuleRuntime {
    @Volatile
    private var configuredModuleApkPath: String? = null

    /*
     * 2026-08-26
     * Change type: add
     * What: 记录模块是否已收到 NPatch Integrated 专用 service，并向设置、入口和心跳路径提供只读判断。
     * Why: NPatch Local 与 Integrated 的 frameworkName 相同，只有 Integrated loader 的 service 投递能可靠证明模块位于单包内。
     * Params & return: updateEmbeddedHostMode 接收已验证的 service 投递状态；isEmbeddedHostMode 返回当前进程是否为内嵌模式。
     * Impact scope: 微信输入法寄生设置保存、关于页入口和激活心跳；独立 LSPosed 安装模式保持原路径。
     * Risk: 第三方 NPatch 若不投递 Integrated service 将回退到独立模块路径，不会误伤 Local 模式。
     */
    @Volatile
    private var embeddedHostMode: Boolean = false

    fun updateModuleApkPath(path: String?) {
        if (path.isNullOrBlank()) return
        configuredModuleApkPath = path
    }

    fun updateEmbeddedHostMode(enabled: Boolean) {
        embeddedHostMode = enabled
    }

    fun isEmbeddedHostMode(): Boolean = embeddedHostMode

    fun resolveModuleApkPath(anchorClass: Class<*> = MainHook::class.java): String? {
        configuredModuleApkPath?.takeIf(::isUsableApkPath)?.let { return it }

        val resolvedFromCodeSource = runCatching {
            anchorClass.protectionDomain?.codeSource?.location?.toURI()?.let { File(it).absolutePath }
        }.getOrNull()
        if (isUsableApkPath(resolvedFromCodeSource)) {
            configuredModuleApkPath = resolvedFromCodeSource
            return resolvedFromCodeSource
        }

        val resolvedFromResource = runCatching {
            anchorClass.getResource("${anchorClass.simpleName}.class")
                ?.toString()
                ?.substringAfter("file:")
                ?.substringBefore("!/")
                ?.takeIf(::isUsableApkPath)
        }.getOrNull()
        if (isUsableApkPath(resolvedFromResource)) {
            configuredModuleApkPath = resolvedFromResource
            return resolvedFromResource
        }

        return null
    }

    private fun isUsableApkPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).exists() && File(path).isFile }.getOrDefault(false)
    }
}
