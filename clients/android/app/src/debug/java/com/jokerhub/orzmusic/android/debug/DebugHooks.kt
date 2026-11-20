package com.jokerhub.orzmusic.android.debug

import android.app.Activity

/**
 * 调试钩子的共享状态。**只存在于 debug 构建**(位于 src/debug/)。
 *
 * 为什么要单独抽一个对象:[DebugHooksInitProvider] 负责写入,[UiDumpReceiver]
 * 负责读取,两者不能互相持有引用。
 */
internal object DebugHooks {

    /** 当前处于 resumed 状态的 Activity;没有则为 null。 */
    @Volatile
    var current: Activity? = null
}
