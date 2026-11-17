package org.avmedia.gshockGoogleSync.h5600

import android.content.Context
import android.content.SharedPreferences

/** The one SharedPreferences file all DW-H5600 additions keep their settings in. */
object H5600Prefs {
    const val NAME = "h5600_prefs"

    /** Weather trick: what the watch's top line was last sent ("W:TT") and when (epoch ms). */
    const val KEY_LAST_SENT_TOP = "last_sent_top"
    const val KEY_LAST_SENT_AT = "last_sent_at"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * The sound mode the user chose on the Custom tab. The watch is kept in it, except while an
     * alarm borrows it (AlarmPool.desiredSoundMode). CUSTOM until the user picks one.
     */
    private const val KEY_BASE_SOUND = "base_sound_mode"

    fun baseSoundMode(context: Context): org.avmedia.gshockapi.model.SoundMode =
        org.avmedia.gshockapi.model.SoundMode.fromCode(get(context).getInt(KEY_BASE_SOUND, -1))
            ?: org.avmedia.gshockapi.model.SoundMode.CUSTOM

    fun setBaseSoundMode(context: Context, mode: org.avmedia.gshockapi.model.SoundMode) =
        get(context).edit().putInt(KEY_BASE_SOUND, mode.code).apply()

    /**
     * "Do not disturb during night time": the watch is silent (no sound, no vibration) in the night
     * window, alarms excepted. The night times are the watch's (0x13), copied here on every read/write.
     */
    private const val KEY_NIGHT_DND = "night_dnd"
    private const val KEY_NIGHT_START = "night_start_min"
    private const val KEY_NIGHT_END = "night_end_min"

    fun nightDndEnabled(context: Context): Boolean = get(context).getBoolean(KEY_NIGHT_DND, false)
    fun setNightDndEnabled(context: Context, on: Boolean) = get(context).edit().putBoolean(KEY_NIGHT_DND, on).apply()

    fun saveNightTime(context: Context, startMin: Int, endMin: Int) =
        get(context).edit().putInt(KEY_NIGHT_START, startMin).putInt(KEY_NIGHT_END, endMin).apply()

    /** The watch's night time (minutes of the day) as last read or written, else null. */
    fun nightTime(context: Context): Pair<Int, Int>? {
        val p = get(context)
        if (!p.contains(KEY_NIGHT_START)) return null
        return p.getInt(KEY_NIGHT_START, 0) to p.getInt(KEY_NIGHT_END, 0)
    }

    /** The night window (minutes of the day) when DND is on and the times are known, else null. */
    fun nightDnd(context: Context): Pair<Int, Int>? {
        val p = get(context)
        if (!nightDndEnabled(context) || !p.contains(KEY_NIGHT_START)) return null
        return p.getInt(KEY_NIGHT_START, 0) to p.getInt(KEY_NIGHT_END, 0)
    }
}
