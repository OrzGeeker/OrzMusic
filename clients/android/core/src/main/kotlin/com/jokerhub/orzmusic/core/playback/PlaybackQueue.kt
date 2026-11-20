package com.jokerhub.orzmusic.core.playback

import com.jokerhub.orzmusic.core.model.Song

/**
 * 平台无关的播放队列。
 *
 * 对齐 Web 端 `app.js` 的 `queue` / `queueIndex` / `activeList`:
 * 播放器本身不拥有队列,队列属于客户端核心。
 */
class PlaybackQueue(initial: List<Song> = emptyList(), startIndex: Int = -1) {

    private val _items: MutableList<Song> = initial.toMutableList()

    var index: Int = startIndex
        private set

    val items: List<Song> get() = _items
    val current: Song? get() = _items.getOrNull(index)
    val hasNext: Boolean get() = index >= 0 && index + 1 < _items.size
    val hasPrevious: Boolean get() = index > 0

    /** 用当前列表 + 目标歌曲重置队列。目标不在列表里就追加。 */
    fun setFrom(list: List<Song>, song: Song) {
        _items.clear()
        _items.addAll(list)
        index = _items.indexOfFirst { it.id == song.id }
        if (index < 0) {
            _items.add(song)
            index = _items.lastIndex
        }
    }

    fun select(song: Song): Boolean {
        val found = _items.indexOfFirst { it.id == song.id }
        if (found < 0) return false
        index = found
        return true
    }

    fun next(): Song? {
        if (!hasNext) return null
        index += 1
        return current
    }

    fun previous(): Song? {
        if (!hasPrevious) return null
        index -= 1
        return current
    }

    fun enqueue(song: Song) {
        if (_items.none { it.id == song.id }) _items.add(song)
    }

    fun remove(songId: String) {
        val i = _items.indexOfFirst { it.id == songId }
        if (i < 0) return
        _items.removeAt(i)
        when {
            _items.isEmpty() -> index = -1
            i < index -> index -= 1
            i == index -> index = index.coerceAtMost(_items.lastIndex)
        }
    }

    /** Web 端的 `activeList`:队列为空时回落到当前列表。 */
    fun activeList(fallback: List<Song>): List<Song> =
        if (_items.isEmpty()) fallback else _items

    fun clear() {
        _items.clear()
        index = -1
    }
}
