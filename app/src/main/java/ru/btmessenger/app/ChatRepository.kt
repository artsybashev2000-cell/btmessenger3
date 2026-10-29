package ru.btmessenger.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * История переписки в SQLite. Раньше сообщения жили только на экране и стирались
 * при каждом подключении — теперь хранятся по адресу собеседника.
 * Методы можно вызывать с любого потока (кроме главного — это работа с диском).
 */
class ChatRepository(context: Context) : SQLiteOpenHelper(context, "chat.db", null, DB_VERSION) {

    private val appContext: Context = context.applicationContext

    /** Удалить файлы звука голосовых сообщений, подходящих под условие [where] (вместе с самими сообщениями они не уходят). */
    private fun deleteVoiceFiles(where: String, args: Array<String>) {
        val ids = ArrayList<String>()
        readableDatabase.rawQuery("SELECT id FROM messages WHERE kind='voice' AND ($where)", args).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        for (id in ids) VoiceStore.delete(appContext, id)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                peer TEXT NOT NULL,
                from_me INTEGER NOT NULL,
                kind TEXT NOT NULL,
                body TEXT NOT NULL,
                lat REAL,
                lon REAL,
                acc REAL,
                fix_time INTEGER,
                sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                status INTEGER NOT NULL,
                group_id TEXT,
                sender TEXT,
                via INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_messages_peer ON messages(peer, seq)")
        db.execSQL("CREATE INDEX idx_messages_group ON messages(group_id)")
        db.execSQL(
            """CREATE TABLE peers (
                address TEXT PRIMARY KEY,
                install_id TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL DEFAULT '',
                role TEXT NOT NULL DEFAULT '',
                device_name TEXT NOT NULL DEFAULT '',
                last_seen INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0)"""
        )
        createGroupTables(db)
    }

    /**
     * Миграции идут по порядку, существующие таблицы и строки не удаляются.
     * 1 → 2: группы. В messages добавляется колонка group_id (у старых строк NULL = личное сообщение),
     * создаются chat_groups и group_members.
     * 2 → 3: колонка messages.sender (кто из участников написал групповое сообщение) и таблица
     * group_delivery (доставка своего группового сообщения по каждому участнику).
     * 3 → 4: колонка messages.via (сколько посредников переслало входящее сообщение, 0 у старых строк).
     * Для новой базы onCreate вызывает те же функции, поэтому схема одинакова.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN group_id TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_group ON messages(group_id)")
            createGroupTables(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sender TEXT")
            createDeliveryTable(db)
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN via INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun createDeliveryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS group_delivery (
                msg_id TEXT NOT NULL,
                install_id TEXT NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (msg_id, install_id))"""
        )
    }

    private fun createGroupTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chat_groups (
                group_id TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                rev INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS group_members (
                group_id TEXT NOT NULL,
                install_id TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (group_id, install_id))"""
        )
        createDeliveryTable(db)
    }

    // ---------- Группы ----------

    /** Создать группу или переименовать существующую (состав и rev не трогает). */
    fun saveGroup(id: String, name: String, createdAt: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("name", name) }
        if (db.update("chat_groups", cv, "group_id=?", arrayOf(id)) == 0) {
            cv.put("group_id", id)
            cv.put("created_at", createdAt)
            db.insertWithOnConflict("chat_groups", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /**
     * Заменить состав группы целиком (кадр GROUP_MEMBERS).
     * @return true, если применено; состав с rev меньше известного игнорируется.
     */
    fun setMembers(groupId: String, rev: Long, members: List<GroupMember>): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val known = db.rawQuery("SELECT rev FROM chat_groups WHERE group_id=?", arrayOf(groupId)).use {
                if (it.moveToFirst()) it.getLong(0) else return false // группы нет — состав без неё не нужен
            }
            if (rev < known) return false
            db.delete("group_members", "group_id=?", arrayOf(groupId))
            for (m in members.take(Protocol.MAX_GROUP_MEMBERS)) insertMember(db, groupId, m)
            db.execSQL("UPDATE chat_groups SET rev=? WHERE group_id=?", arrayOf<Any>(rev, groupId))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Добавить участника (или обновить имя); rev группы растёт на 1. */
    fun addMember(groupId: String, member: GroupMember) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertMember(db, groupId, member)
            db.execSQL("UPDATE chat_groups SET rev = rev + 1 WHERE group_id=?", arrayOf<Any>(groupId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Убрать участника; rev группы растёт на 1. */
    fun removeMember(groupId: String, installId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("group_members", "group_id=? AND install_id=?", arrayOf(groupId, installId))
            db.execSQL("UPDATE chat_groups SET rev = rev + 1 WHERE group_id=?", arrayOf<Any>(groupId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertMember(db: SQLiteDatabase, groupId: String, m: GroupMember) {
        val cv = ContentValues().apply {
            put("group_id", groupId)
            put("install_id", m.installId)
            put("name", m.name)
        }
        db.insertWithOnConflict("group_members", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun group(id: String): GroupInfo? = groups("WHERE g.group_id=?", arrayOf(id)).firstOrNull()

    /** Все группы: сначала с самым свежим сообщением. */
    fun groups(): List<GroupInfo> = groups("", emptyArray())

    private fun groups(where: String, args: Array<String>): List<GroupInfo> {
        val db = readableDatabase
        val out = ArrayList<GroupInfo>()
        db.rawQuery(
            """SELECT g.group_id, g.name, g.rev, g.created_at, g.unread
               FROM chat_groups g $where
               ORDER BY COALESCE((SELECT MAX(seq) FROM messages WHERE group_id = g.group_id), 0) DESC, g.created_at DESC""",
            args
        ).use { c ->
            while (c.moveToNext()) {
                out += GroupInfo(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4), emptyList())
            }
        }
        return out.map { g ->
            val members = db.rawQuery(
                "SELECT install_id, name FROM group_members WHERE group_id=? ORDER BY rowid",
                arrayOf(g.id)
            ).use { c ->
                val list = ArrayList<GroupMember>(c.count)
                while (c.moveToNext()) list += GroupMember(c.getString(0), c.getString(1))
                list
            }
            g.copy(members = members)
        }
    }

    fun incGroupUnread(groupId: String) {
        writableDatabase.execSQL("UPDATE chat_groups SET unread = unread + 1 WHERE group_id=?", arrayOf<Any>(groupId))
    }

    fun clearGroupUnread(groupId: String) {
        writableDatabase.execSQL("UPDATE chat_groups SET unread = 0 WHERE group_id=?", arrayOf<Any>(groupId))
    }

    /** Удалить группу вместе с её составом и перепиской. */
    fun deleteGroup(groupId: String) {
        val db = writableDatabase
        deleteVoiceFiles("group_id=?", arrayOf(groupId))
        db.beginTransaction()
        try {
            db.delete("group_delivery", "msg_id IN (SELECT id FROM messages WHERE group_id=?)", arrayOf(groupId))
            db.delete("messages", "group_id=?", arrayOf(groupId))
            db.delete("group_members", "group_id=?", arrayOf(groupId))
            db.delete("chat_groups", "group_id=?", arrayOf(groupId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Собеседники ----------

    /** Обновить сведения о собеседнике (пустые значения не затирают известные). */
    fun upsertPeer(address: String, installId: String?, name: String?, role: String?, deviceName: String?) {
        val db = writableDatabase
        val cv = ContentValues()
        if (!installId.isNullOrBlank()) cv.put("install_id", installId)
        if (name != null && (name.isNotBlank() || !installId.isNullOrBlank())) cv.put("name", name)
        if (role != null && (role.isNotBlank() || !installId.isNullOrBlank())) cv.put("role", role)
        if (!deviceName.isNullOrBlank()) cv.put("device_name", deviceName)
        cv.put("last_seen", System.currentTimeMillis())
        if (db.update("peers", cv, "address=?", arrayOf(address)) == 0) {
            cv.put("address", address)
            db.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun peer(address: String): PeerRecord? =
        readableDatabase.rawQuery(
            "SELECT address, install_id, name, role, device_name FROM peers WHERE address=?",
            arrayOf(address)
        ).use { c ->
            if (!c.moveToFirst()) null
            else PeerRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }

    /** Собеседник по id установки (если он с разных адресов — тот, что виден последним). */
    fun peerByInstallId(installId: String): PeerRecord? {
        if (installId.isBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT address, install_id, name, role, device_name FROM peers WHERE install_id=? ORDER BY last_seen DESC LIMIT 1",
            arrayOf(installId)
        ).use { c ->
            if (!c.moveToFirst()) null
            else PeerRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }
    }

    /** Знакомые собеседники — те, от кого приходил HELLO (известен id установки): их можно звать в группу. */
    fun knownPeers(): List<PeerRecord> =
        readableDatabase.rawQuery(
            "SELECT address, install_id, name, role, device_name FROM peers WHERE install_id<>'' ORDER BY last_seen DESC",
            null
        ).use { c ->
            val out = ArrayList<PeerRecord>(c.count)
            while (c.moveToNext()) {
                out += PeerRecord(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            }
            out
        }

    /** Группы для списка чатов: с последним сообщением и именем его автора. */
    fun groupSummaries(): List<GroupSummary> {
        val db = readableDatabase
        return groups().map { g ->
            db.rawQuery(
                "SELECT kind, body, from_me, sent_at, sender, seq FROM messages WHERE group_id=? ORDER BY seq DESC LIMIT 1",
                arrayOf(g.id)
            ).use { c ->
                if (c.moveToFirst()) {
                    val fromMe = c.getInt(2) != 0
                    val sender = if (c.isNull(4)) "" else c.getString(4)
                    val author = if (fromMe || sender.isEmpty()) "" else {
                        g.members.firstOrNull { it.installId == sender }?.name?.takeIf { it.isNotBlank() }
                            ?: peerByInstallId(sender)?.title.orEmpty()
                    }
                    GroupSummary(g, Kind.of(c.getString(0)), c.getString(1), fromMe, author, c.getLong(3), c.getLong(5))
                } else {
                    GroupSummary(g, null, null, false, "", g.createdAt, 0L)
                }
            }
        }
    }

    fun summaries(): List<PeerSummary> =
        readableDatabase.rawQuery(
            """SELECT p.address, p.name, p.role, p.device_name, p.unread,
                      m.kind, m.body, m.from_me, m.sent_at, m.seq, p.last_seen
               FROM peers p
               LEFT JOIN messages m ON m.seq = (SELECT MAX(seq) FROM messages WHERE peer = p.address)
               ORDER BY COALESCE(m.seq, 0) DESC, p.last_seen DESC""",
            null
        ).use { c ->
            val out = ArrayList<PeerSummary>(c.count)
            while (c.moveToNext()) {
                val hasMsg = !c.isNull(5)
                out += PeerSummary(
                    address = c.getString(0),
                    name = c.getString(1),
                    role = c.getString(2),
                    deviceName = c.getString(3),
                    unread = c.getInt(4),
                    lastKind = if (hasMsg) Kind.of(c.getString(5)) else null,
                    lastText = if (hasMsg) c.getString(6) else null,
                    lastFromMe = hasMsg && c.getInt(7) != 0,
                    lastTime = if (hasMsg) c.getLong(8) else c.getLong(10),
                    lastSeq = if (hasMsg) c.getLong(9) else 0L
                )
            }
            out
        }

    fun incUnread(address: String) {
        writableDatabase.execSQL("UPDATE peers SET unread = unread + 1 WHERE address=?", arrayOf<Any>(address))
    }

    fun clearUnread(address: String) {
        writableDatabase.execSQL("UPDATE peers SET unread = 0 WHERE address=?", arrayOf<Any>(address))
    }

    fun deletePeer(address: String) {
        val db = writableDatabase
        deleteVoiceFiles("peer=?", arrayOf(address))
        db.beginTransaction()
        try {
            db.delete("messages", "peer=?", arrayOf(address))
            db.delete("peers", "address=?", arrayOf(address))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Сообщения ----------

    fun insertOutgoing(m: ChatMessage) {
        writableDatabase.insertWithOnConflict("messages", null, values(m), SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** @return true, если сообщение новое (повторы после переподключения отбрасываются по id). */
    fun insertIncoming(m: ChatMessage): Boolean =
        writableDatabase.insertWithOnConflict("messages", null, values(m), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun message(id: String): ChatMessage? =
        query("SELECT * FROM messages WHERE id=?", arrayOf(id)).firstOrNull()

    /**
     * Заменить точку у SOS более свежей (по времени замера). Нужно, чтобы уточнённые координаты
     * обновляли то же сообщение, а не появлялись отдельным.
     * @return true, если точка обновлена (старее или такой же замер игнорируется).
     */
    fun refreshGeo(id: String, peer: String, fromMe: Boolean, geo: GeoPoint): Boolean {
        val cv = ContentValues().apply {
            put("lat", geo.lat)
            put("lon", geo.lon)
            if (geo.accuracy != null) put("acc", geo.accuracy.toDouble()) else putNull("acc")
            put("fix_time", geo.fixTime)
        }
        return writableDatabase.update(
            "messages", cv,
            "id=? AND peer=? AND from_me=? AND kind='sos' AND (lat IS NULL OR fix_time IS NULL OR fix_time<?)",
            arrayOf(id, peer, if (fromMe) "1" else "0", geo.fixTime.toString())
        ) > 0
    }

    /** Повышает статус своего сообщения (PENDING → SENT → DELIVERED), понижать нельзя. */
    fun upgradeStatus(id: String, peer: String, status: Status): Boolean {
        val cv = ContentValues().apply { put("status", status.code) }
        return writableDatabase.update(
            "messages", cv,
            "id=? AND peer=? AND from_me=1 AND status<?",
            arrayOf(id, peer, status.code.toString())
        ) > 0
    }

    // ---------- Доставка групповых сообщений по участникам ----------

    /** Завести строки доставки (статус PENDING) для участников, которым адресовано сообщение. */
    fun initGroupDelivery(msgId: String, installIds: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in installIds) {
                val cv = ContentValues().apply {
                    put("msg_id", msgId)
                    put("install_id", id)
                    put("status", Status.PENDING.code)
                }
                db.insertWithOnConflict("group_delivery", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Повысить статус доставки одному участнику (понижать нельзя). @return true, если статус изменился. */
    fun setGroupDelivery(msgId: String, installId: String, status: Status): Boolean {
        val cv = ContentValues().apply { put("status", status.code) }
        return writableDatabase.update(
            "group_delivery", cv,
            "msg_id=? AND install_id=? AND status<?",
            arrayOf(msgId, installId, status.code.toString())
        ) > 0
    }

    /** Доставка сообщения по участникам: id установки → статус. Только текущие участники группы. */
    fun groupDelivery(msgId: String): Map<String, Status> {
        val out = LinkedHashMap<String, Status>()
        readableDatabase.rawQuery(
            """SELECT d.install_id, d.status FROM group_delivery d
               JOIN messages m ON m.id = d.msg_id
               JOIN group_members gm ON gm.group_id = m.group_id AND gm.install_id = d.install_id
               WHERE d.msg_id=? ORDER BY gm.rowid""",
            arrayOf(msgId)
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = Status.of(c.getInt(1))
        }
        return out
    }

    /** Свои групповые сообщения, которые этот участник (ещё состоящий в группе) не подтвердил. */
    fun groupUnacked(installId: String): List<ChatMessage> =
        query(
            """SELECT m.* FROM messages m
               JOIN group_delivery d ON d.msg_id = m.id
               JOIN group_members gm ON gm.group_id = m.group_id AND gm.install_id = d.install_id
               WHERE d.install_id=? AND d.status<? AND m.from_me=1
               ORDER BY m.seq""",
            arrayOf(installId, Status.DELIVERED.code.toString())
        )

    /** Свои сообщения, получение которых собеседник ещё не подтвердил. */
    fun unacked(peer: String): List<ChatMessage> =
        query("SELECT * FROM messages WHERE peer=? AND from_me=1 AND status<? ORDER BY seq",
            arrayOf(peer, Status.DELIVERED.code.toString()))

    fun messages(peer: String, limit: Int = 500): List<ChatMessage> =
        query("SELECT * FROM (SELECT * FROM messages WHERE peer=? ORDER BY seq DESC LIMIT ${limit.coerceIn(1, 5000)}) ORDER BY seq",
            arrayOf(peer))

    fun clearHistory(peer: String) {
        val groupId = groupIdFromKey(peer)
        if (groupId != null) {
            // Групповой чат: вместе с перепиской уходят строки доставки, счётчик — в таблице групп
            val db = writableDatabase
            deleteVoiceFiles("group_id=?", arrayOf(groupId))
            db.beginTransaction()
            try {
                db.delete("group_delivery", "msg_id IN (SELECT id FROM messages WHERE group_id=?)", arrayOf(groupId))
                db.delete("messages", "group_id=?", arrayOf(groupId))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            clearGroupUnread(groupId)
            return
        }
        deleteVoiceFiles("peer=?", arrayOf(peer))
        writableDatabase.delete("messages", "peer=?", arrayOf(peer))
        clearUnread(peer)
    }

    private companion object {
        const val DB_VERSION = 4
    }

    private fun query(sql: String, args: Array<String>): List<ChatMessage> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<ChatMessage>(c.count)
            while (c.moveToNext()) out += read(c)
            out
        }

    private fun values(m: ChatMessage) = ContentValues().apply {
        put("id", m.id)
        put("peer", m.peer)
        put("from_me", if (m.fromMe) 1 else 0)
        put("kind", m.kind.code)
        put("body", m.text)
        val g = m.geo
        if (g != null) {
            put("lat", g.lat)
            put("lon", g.lon)
            if (g.accuracy != null) put("acc", g.accuracy.toDouble())
            put("fix_time", g.fixTime)
        }
        put("sent_at", m.sentAt)
        put("received_at", m.receivedAt)
        put("status", m.status.code)
        if (m.groupId != null) put("group_id", m.groupId) else putNull("group_id")
        if (m.senderId != null) put("sender", m.senderId) else putNull("sender")
        put("via", m.via)
    }

    private fun read(c: Cursor): ChatMessage {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        val geo = if (c.isNull(col("lat")) || c.isNull(col("lon"))) null else GeoPoint(
            lat = c.getDouble(col("lat")),
            lon = c.getDouble(col("lon")),
            accuracy = if (c.isNull(col("acc"))) null else c.getDouble(col("acc")).toFloat(),
            fixTime = if (c.isNull(col("fix_time"))) c.getLong(col("sent_at")) else c.getLong(col("fix_time"))
        )
        return ChatMessage(
            id = c.getString(col("id")),
            peer = c.getString(col("peer")),
            fromMe = c.getInt(col("from_me")) != 0,
            kind = Kind.of(c.getString(col("kind"))) ?: Kind.TEXT,
            text = c.getString(col("body")),
            geo = geo,
            sentAt = c.getLong(col("sent_at")),
            receivedAt = c.getLong(col("received_at")),
            status = Status.of(c.getInt(col("status"))),
            groupId = c.getColumnIndex("group_id").let { i -> if (i < 0 || c.isNull(i)) null else c.getString(i) },
            senderId = c.getColumnIndex("sender").let { i -> if (i < 0 || c.isNull(i)) null else c.getString(i) },
            via = c.getColumnIndex("via").let { i -> if (i < 0 || c.isNull(i)) 0 else c.getInt(i) }
        )
    }
}
