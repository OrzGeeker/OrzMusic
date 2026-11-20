package com.jokerhub.orzmusic.android.debug

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 借 ContentProvider 的自动初始化时机拿到 Application,注册 Activity 生命周期回调,
 * 从而跟踪「当前是哪个 Activity」。
 *
 * **这样做的意义是零侵入**:业务代码的 Application / Activity 一行都不用改,
 * 只要把 src/debug/ 下的文件放进工程、并在 src/debug/AndroidManifest.xml 里
 * 声明这个 provider 即可。
 *
 * ContentProvider 会在 Application.onCreate 之后、任何 Activity 之前被创建,
 * 时机刚好。
 */
class DebugHooksInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                DebugHooks.current = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (DebugHooks.current === activity) DebugHooks.current = null
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (DebugHooks.current === activity) DebugHooks.current = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        return true
    }

    // 这个 provider 只用来抢初始化时机,不提供任何数据
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
