package ru.btmessenger.app

import android.content.Context
import java.util.UUID

/** Настройки: профиль пользователя, id установки, список доверенных собеседников. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Постоянный случайный id этой установки — по нему телефоны узнают друг друга. */
    val installId: String by lazy {
        sp.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_INSTALL_ID, it).apply()
        }
    }

    var userName: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_NAME, value.cleanLine(64)).apply()

    var userRole: String
        get() = sp.getString(KEY_ROLE, "") ?: ""
        set(value) = sp.edit().putString(KEY_ROLE, value.cleanLine(64)).apply()

    var profileAsked: Boolean
        get() = sp.getBoolean(KEY_PROFILE_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_PROFILE_ASKED, value).apply()

    var notificationsAsked: Boolean
        get() = sp.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    var dndAsked: Boolean
        get() = sp.getBoolean(KEY_DND_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_DND_ASKED, value).apply()

    /** Подсказку про микрофон («нажмите — сообщение, удерживайте — рация») показываем один раз. */
    var voiceHintShown: Boolean
        get() = sp.getBoolean(KEY_VOICE_HINT, false)
        set(value) = sp.edit().putBoolean(KEY_VOICE_HINT, value).apply()

    var showAllDevices: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_ALL, value).apply()

    /**
     * Пересылать ли чужие сообщения дальше. По умолчанию да. Выключено: телефон ничего не пересылает
     * (ни сообщения, ни подтверждения), но сообщения, адресованные ему, получает как обычно.
     * Пересылка расходует заряд батареи и радиоканал.
     */
    var relayEnabled: Boolean
        get() = sp.getBoolean(KEY_RELAY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_RELAY_ENABLED, value).apply()

    /** Сколько чужих сообщений телефон переслал дальше (одно сообщение считается один раз, сколько бы соседей его ни получило). */
    val relayedCount: Long get() = sp.getLong(KEY_RELAYED_COUNT, 0L)

    @Synchronized
    fun countRelayed() {
        sp.edit().putLong(KEY_RELAYED_COUNT, relayedCount + 1).apply()
    }

    @Synchronized
    fun resetRelayedCount() {
        sp.edit().putLong(KEY_RELAYED_COUNT, 0L).apply()
    }

    /**
     * Пересылать ли сообщения групп, в которых пользователь не состоит. По умолчанию нет: чужие
     * групповые переписки проходят через телефон только с явного разрешения.
     */
    var relayForeignGroups: Boolean
        get() = sp.getBoolean(KEY_RELAY_FOREIGN_GROUPS, false)
        set(value) = sp.edit().putBoolean(KEY_RELAY_FOREIGN_GROUPS, value).apply()

    /**
     * Доверенные — те, к кому вы подключались сами или чьё подключение приняли.
     * От них входящее подключение принимается без вопроса (нужно для автопереподключения).
     */
    @Synchronized
    fun isTrusted(address: String): Boolean = trusted().contains(address)

    @Synchronized
    fun trust(address: String) {
        val set = HashSet(trusted())
        if (set.add(address)) sp.edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    @Synchronized
    fun untrust(address: String) {
        val set = HashSet(trusted())
        if (set.remove(address)) sp.edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    private fun trusted(): Set<String> = sp.getStringSet(KEY_TRUSTED, null) ?: emptySet()

    /**
     * Группы, из которых пользователь вышел. Пока кто-то из участников не узнал о выходе, он может
     * прислать приглашение или состав снова — тогда мы отвечаем «я вышел» и группу не восстанавливаем.
     */
    @Synchronized
    fun hasLeftGroup(groupId: String): Boolean = leftGroups().contains(groupId)

    @Synchronized
    fun markLeftGroup(groupId: String) {
        val set = HashSet(leftGroups())
        if (set.add(groupId)) sp.edit().putStringSet(KEY_LEFT_GROUPS, set).apply()
    }

    // ---------- «Человек не двигается» (StillnessDetector) ----------

    /** Функция выключена, пока пользователь сам не включит: пока она выключена, акселерометр не запускается. */
    var stillEnabled: Boolean
        get() = sp.getBoolean(KEY_STILL_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_STILL_ENABLED, value).apply()

    /** Через сколько секунд неподвижности предлагать SOS. */
    var stillSeconds: Int
        get() = sp.getInt(KEY_STILL_SECONDS, StillnessConfig.DEFAULT_SECONDS)
            .coerceIn(StillnessConfig.MIN_SECONDS, StillnessConfig.MAX_SECONDS)
        set(value) = sp.edit().putInt(KEY_STILL_SECONDS, value.coerceIn(StillnessConfig.MIN_SECONDS, StillnessConfig.MAX_SECONDS)).apply()

    /** Сколько секунд даём ответить на предупреждение, прежде чем отправить SOS. */
    var stillReplySeconds: Int
        get() = sp.getInt(KEY_STILL_REPLY, StillnessConfig.DEFAULT_REPLY_SECONDS)
            .coerceIn(StillnessConfig.MIN_REPLY_SECONDS, StillnessConfig.MAX_REPLY_SECONDS)
        set(value) = sp.edit().putInt(KEY_STILL_REPLY, value.coerceIn(StillnessConfig.MIN_REPLY_SECONDS, StillnessConfig.MAX_REPLY_SECONDS)).apply()

    /** Чувствительность к движению: 0 низкая, 1 средняя, 2 высокая (см. [StillSensitivity]). */
    var stillSensitivity: Int
        get() = sp.getInt(KEY_STILL_SENSITIVITY, StillnessConfig.DEFAULT_SENSITIVITY).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_STILL_SENSITIVITY, value.coerceIn(0, 2)).apply()

    /** Не считать тревогой телефон, который ровно и тихо лежит (скорее всего на столе). */
    var stillIgnoreSurface: Boolean
        get() = sp.getBoolean(KEY_STILL_IGNORE_SURFACE, StillnessConfig.DEFAULT_IGNORE_SURFACE)
        set(value) = sp.edit().putBoolean(KEY_STILL_IGNORE_SURFACE, value).apply()

    private fun leftGroups(): Set<String> = sp.getStringSet(KEY_LEFT_GROUPS, null) ?: emptySet()

    private companion object {
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_NAME = "user_name"
        const val KEY_ROLE = "user_role"
        const val KEY_PROFILE_ASKED = "profile_asked"
        const val KEY_SHOW_ALL = "show_all_devices"
        const val KEY_VOICE_HINT = "voice_hint_shown"
        const val KEY_NOTIF_ASKED = "notifications_asked"
        const val KEY_TRUSTED = "trusted"
        const val KEY_DND_ASKED = "dnd_asked"
        const val KEY_LEFT_GROUPS = "left_groups"
        const val KEY_RELAY_FOREIGN_GROUPS = "relay_foreign_groups"
        const val KEY_RELAY_ENABLED = "relay_enabled"
        const val KEY_RELAYED_COUNT = "relayed_count"
        const val KEY_STILL_ENABLED = "still_enabled"
        const val KEY_STILL_SECONDS = "still_seconds"
        const val KEY_STILL_REPLY = "still_reply_seconds"
        const val KEY_STILL_SENSITIVITY = "still_sensitivity"
        const val KEY_STILL_IGNORE_SURFACE = "still_ignore_surface"
    }
}
