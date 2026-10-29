package ru.btmessenger.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Протокол обмена между телефонами.
 *
 * Каждый кадр: 4 байта длины (big-endian) + JSON в UTF-8. Поле "t" — тип кадра.
 * Неизвестные типы кадров и лишние поля игнорируются, поэтому новые версии приложения
 * могут добавлять свои кадры, не ломая старые. Формат простой и не завязан на Java,
 * его легко разобрать и на микроконтроллере (например, ESP32-метке).
 *
 * Порядок при подключении:
 *   1. обе стороны сразу шлют HELLO (версия, id установки, имя, должность);
 *   2. принимающая сторона отвечает ACCEPT (или REJECT) — сразу, если собеседник
 *      уже знаком, иначе после подтверждения пользователем;
 *   3. дальше идут MSG, на каждое — ACK с тем же id; PING/PONG держат связь;
 *   4. BYE — осознанное отключение (без автопереподключения).
 *
 * Группы (версия протокола 2). Группа — это строковый id (генерирует создатель, ≤ 64 символов),
 * название и список участников (id установки + имя). В MSG необязательное поле "g" — id группы;
 * нет поля — личное сообщение. Кадры управления группой:
 *   GROUP_INVITE  {"t":"group_invite","g":id,"gname":название,"members":[{"id":..,"name":..}]}
 *                 — приглашение: что за группа и кто в ней сейчас;
 *   GROUP_JOIN    {"t":"group_join","g":id,"id":id установки,"name":имя}
 *                 — «я вступил» (ответ на приглашение или объявление о новом участнике);
 *   GROUP_LEAVE   {"t":"group_leave","g":id,"id":id установки}
 *                 — «участник вышел» (свой выход или исключение, id указывает кто);
 *   GROUP_MEMBERS {"t":"group_members","g":id,"rev":номер версии состава,"members":[..]}
 *                 — полный список участников; применяется, если rev не меньше известного.
 *
 * Пересылка по цепочке (версия протокола 2, необязательные поля MSG; правила — в MeshRules.kt):
 *   "origin" — id установки автора; "dest" — id получателя (id установки) или группы ("group:<id>");
 *   "ttl"    — сколько раз кадр ещё можно переслать; "hops" — id узлов, через которые он уже прошёл
 *              (первый — автор). Нет поля "origin" — кадр прямой, доставленный по одному каналу.
 *   ACK пересланного сообщения идёт обратно по пути hops: {"t":"ack","id":..,"to":автор,"route":[..],"from":кто подтвердил}.
 *   "route" — узлы, которые ACK ещё должен пройти (первый — получатель этого кадра, последний — автор).
 *   Нет поля "to" — обычный ACK соседу.
 *
 * Голос (версия протокола 4; только прямое соединение, без групп и пересылки). Все данные — JSON, звук в base64:
 *   VOICE_START {"t":"voice_start","id":id,"live":true|false,"codec":"amr-wb","ms":40,"ts":время}
 *               — начало. live = рация (куски идут по ходу записи), иначе голосовое сообщение;
 *                 ms — длина одного куска; codec — формат звука (неизвестный получатель игнорирует);
 *   VOICE_CHUNK {"t":"voice_chunk","id":id,"n":номер с 0,"d":base64}
 *   VOICE_END   {"t":"voice_end","id":id,"n":сколько кусков всего}
 *   VOICE_NACK  {"t":"voice_nack","id":id,"missing":[номера]} — получатель просит досылать недостающие куски
 *               сообщения. Принятое целиком сообщение подтверждается обычным ACK с тем же id.
 */
object Protocol {
    /**
     * 3 — добавлена пересылка по цепочке (поля origin/dest/ttl/hops в MSG и to/route/from в ACK).
     * 4 — голос: кадры voice_start / voice_chunk / voice_end / voice_nack.
     */
    const val VERSION = 4

    /** Телефоны старее не знают голосовые кадры (они их проигнорируют): им голос не шлём. */
    const val VOICE_MIN_VERSION = 4
    const val VOICE_CODEC_AMR_WB = "amr-wb"

    /** Один кусок звука: 1-3 кадра AMR-WB по ≤ 61 байту, с запасом. */
    const val MAX_VOICE_CHUNK_BYTES = 512

    /** Предел номера куска и счётчика в кадрах (рация идёт долго, поэтому с запасом). */
    const val MAX_VOICE_SEQ = 1_000_000

    /** Голосовое сообщение: не больше стольких кусков, секунд и байт (при 40 мс на кусок 1500 кусков = 60 с). */
    const val MAX_VOICE_CHUNKS = 3000
    const val MAX_VOICE_MS = 60_000
    const val MAX_VOICE_BYTES = 256 * 1024

    /** Сколько недостающих номеров умещается в один voice_nack. */
    const val MAX_NACK = 100

    /** Телефоны старее не знают поле "origin" и приняли бы пересланный кадр за прямой: им не пересылаем. */
    const val MESH_MIN_VERSION = 3
    const val MAX_FRAME_BYTES = 64 * 1024
    const val MAX_TEXT = 4000
    const val MAX_ID = 64
    const val MAX_NAME = 64
    const val MAX_GROUP_MEMBERS = 32

    /** Сколько раз кадр можно переслать (автор ставит это значение). Обоснование — в MeshRules.kt. */
    const val MAX_TTL = 3

    /** Автор + все пересылающие: не больше MAX_TTL + 1 узлов. */
    const val MAX_HOPS = MAX_TTL + 1

    /** Получатель: id установки или "group:" + id группы. */
    const val MAX_DEST = MAX_ID + 6

    /**
     * UUID службы. Изменён по сравнению с версией 0.1 (writeUTF), чтобы старая и новая
     * версии просто не соединялись, а не принимали друг от друга мусор.
     */
    val APP_UUID: UUID = UUID.fromString("5d3f9b2a-7c41-4e0b-9f6d-1a8e2c7b4f90")
    const val SERVICE_NAME = "BtMessenger"

    @Throws(IOException::class)
    fun write(out: DataOutputStream, frame: Frame) {
        val bytes = frame.toJson().toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) throw IOException("frame too large: ${bytes.size}")
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    @Throws(IOException::class)
    fun read(inp: DataInputStream): Frame {
        val len = inp.readInt()
        if (len <= 0 || len > MAX_FRAME_BYTES) throw IOException("bad frame length: $len")
        val buf = ByteArray(len)
        inp.readFully(buf)
        val json = try {
            JSONObject(String(buf, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException("bad frame json", e)
        }
        return Frame.fromJson(json)
    }
}

sealed class Frame {
    abstract fun toJson(): JSONObject

    data class Hello(val version: Int, val installId: String, val name: String, val role: String) : Frame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "hello").put("v", version).put("id", installId).put("name", name).put("role", role)
    }

    object Accept : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "accept")
    }

    data class Reject(val reason: String) : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "reject").put("reason", reason)
    }

    data class Msg(val message: WireMessage) : Frame() {
        override fun toJson(): JSONObject = message.toJson().put("t", "msg")
    }

    /**
     * Подтверждение получения. Обычный (to = null) — соседу по каналу. Пересланный: [to] — автор сообщения,
     * [route] — узлы, которые кадр ещё пройдёт (первый — тот, кому кадр отправлен), [from] — кто подтвердил.
     */
    data class Ack(
        val id: String,
        val to: String? = null,
        val route: List<String> = emptyList(),
        val from: String? = null
    ) : Frame() {
        override fun toJson(): JSONObject {
            val j = JSONObject().put("t", "ack").put("id", id)
            if (to != null) {
                j.put("to", to).put("route", JSONArray(route))
                if (from != null) j.put("from", from)
            }
            return j
        }
    }

    object Ping : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "ping")
    }

    object Pong : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "pong")
    }

    object Bye : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "bye")
    }

    /** Кадры управления группой. Общий родитель — чтобы получатель мог обработать их одной веткой. */
    sealed class GroupFrame : Frame() {
        abstract val groupId: String
    }

    data class GroupInvite(override val groupId: String, val name: String, val members: List<GroupMember>) : GroupFrame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "group_invite").put("g", groupId).put("gname", name).put("members", members.toJsonArray())
    }

    data class GroupJoin(override val groupId: String, val member: GroupMember) : GroupFrame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "group_join").put("g", groupId).put("id", member.installId).put("name", member.name)
    }

    data class GroupLeave(override val groupId: String, val installId: String) : GroupFrame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "group_leave").put("g", groupId).put("id", installId)
    }

    data class GroupMembers(override val groupId: String, val rev: Long, val members: List<GroupMember>) : GroupFrame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "group_members").put("g", groupId).put("rev", rev).put("members", members.toJsonArray())
    }

    /** Начало голосовой передачи: [live] — рация, иначе голосовое сообщение. */
    data class VoiceStart(val id: String, val live: Boolean, val codec: String, val chunkMs: Int, val sentAt: Long) : Frame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "voice_start").put("id", id).put("live", live).put("codec", codec).put("ms", chunkMs).put("ts", sentAt)
    }

    /** Кусок звука с порядковым номером [seq] (с 0). */
    class VoiceChunk(val id: String, val seq: Int, val data: ByteArray) : Frame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "voice_chunk").put("id", id).put("n", seq).put("d", Base64Lite.encode(data))

        override fun equals(other: Any?): Boolean =
            other is VoiceChunk && id == other.id && seq == other.seq && data.contentEquals(other.data)

        override fun hashCode(): Int = (id.hashCode() * 31 + seq) * 31 + data.contentHashCode()
    }

    /** Конец передачи: [count] — сколько кусков было отправлено (номера 0..count-1). */
    data class VoiceEnd(val id: String, val count: Int) : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", "voice_end").put("id", id).put("n", count)
    }

    /** Получатель не досчитался кусков сообщения [id]: просит прислать [missing] и конец ещё раз. */
    data class VoiceNack(val id: String, val missing: List<Int>) : Frame() {
        override fun toJson(): JSONObject = JSONObject()
            .put("t", "voice_nack").put("id", id).put("missing", JSONArray(missing.take(Protocol.MAX_NACK)))
    }

    data class Unknown(val type: String) : Frame() {
        override fun toJson(): JSONObject = JSONObject().put("t", type)
    }

    companion object {
        const val REJECT_DECLINED = "declined"
        const val REJECT_BUSY = "busy"
        const val REJECT_TIMEOUT = "timeout"

        fun fromJson(j: JSONObject): Frame = when (val t = j.optString("t")) {
            "hello" -> Hello(
                version = j.optInt("v", 1),
                installId = j.optString("id").trim().take(64),
                name = j.optString("name").cleanLine(64),
                role = j.optString("role").cleanLine(64)
            )
            "accept" -> Accept
            "reject" -> Reject(j.optString("reason").take(32))
            "msg" -> WireMessage.fromJson(j)?.let { Msg(it) } ?: Unknown(t)
            "ack" -> parseAck(j) ?: Unknown(t)
            "ping" -> Ping
            "pong" -> Pong
            "bye" -> Bye
            "group_invite" -> validGroupId(j.optString("g"))?.let {
                GroupInvite(it, j.optString("gname").cleanLine(Protocol.MAX_NAME), GroupMember.listFromJson(j.optJSONArray("members")))
            } ?: Unknown(t)
            "group_join" -> {
                val gid = validGroupId(j.optString("g"))
                val id = j.optString("id").trim()
                if (gid == null || id.isEmpty() || id.length > Protocol.MAX_ID) Unknown(t)
                else GroupJoin(gid, GroupMember(id, j.optString("name").cleanLine(Protocol.MAX_NAME)))
            }
            "group_leave" -> {
                val gid = validGroupId(j.optString("g"))
                val id = j.optString("id").trim()
                if (gid == null || id.isEmpty() || id.length > Protocol.MAX_ID) Unknown(t) else GroupLeave(gid, id)
            }
            "group_members" -> validGroupId(j.optString("g"))?.let {
                GroupMembers(it, j.optLong("rev", 0L).coerceAtLeast(0L), GroupMember.listFromJson(j.optJSONArray("members")))
            } ?: Unknown(t)
            "voice_start" -> parseVoiceStart(j) ?: Unknown(t)
            "voice_chunk" -> parseVoiceChunk(j) ?: Unknown(t)
            "voice_end" -> parseVoiceEnd(j) ?: Unknown(t)
            "voice_nack" -> parseVoiceNack(j) ?: Unknown(t)
            else -> Unknown(t.take(32))
        }

        private fun parseVoiceStart(j: JSONObject): VoiceStart? {
            val id = cleanId(j.optString("id"), Protocol.MAX_ID) ?: return null
            val ms = j.optInt("ms", 0)
            if (ms !in 10..200) return null
            return VoiceStart(
                id, j.optBoolean("live", false), j.optString("codec").cleanLine(16), ms,
                j.optLong("ts", System.currentTimeMillis())
            )
        }

        private fun parseVoiceChunk(j: JSONObject): VoiceChunk? {
            val id = cleanId(j.optString("id"), Protocol.MAX_ID) ?: return null
            val n = j.optInt("n", -1)
            if (n !in 0..Protocol.MAX_VOICE_SEQ) return null
            val d = j.optString("d")
            if (d.isEmpty() || d.length > Protocol.MAX_VOICE_CHUNK_BYTES * 2) return null
            val data = Base64Lite.decode(d) ?: return null
            if (data.isEmpty() || data.size > Protocol.MAX_VOICE_CHUNK_BYTES) return null
            return VoiceChunk(id, n, data)
        }

        private fun parseVoiceEnd(j: JSONObject): VoiceEnd? {
            val id = cleanId(j.optString("id"), Protocol.MAX_ID) ?: return null
            val n = j.optInt("n", -1)
            return if (n in 0..Protocol.MAX_VOICE_SEQ) VoiceEnd(id, n) else null
        }

        private fun parseVoiceNack(j: JSONObject): VoiceNack? {
            val id = cleanId(j.optString("id"), Protocol.MAX_ID) ?: return null
            val arr = j.optJSONArray("missing") ?: return null
            val out = ArrayList<Int>()
            for (i in 0 until minOf(arr.length(), Protocol.MAX_NACK)) {
                val v = arr.optInt(i, -1)
                if (v in 0 until Protocol.MAX_VOICE_CHUNKS) out.add(v)
            }
            return if (out.isEmpty()) null else VoiceNack(id, out)
        }

        /**
         * Обычный ACK или пересланный. Негодный пересланный (нет пути, путь не кончается автором, мусор)
         * отбрасываем целиком: иначе он сошёл бы за обычный подтверждающий соседа.
         */
        private fun parseAck(j: JSONObject): Ack? {
            val id = j.optString("id").take(64)
            if (!j.has("to") || j.isNull("to")) return Ack(id)
            val to = cleanId(j.optString("to"), Protocol.MAX_ID) ?: return null
            val route = parseIdList(j.optJSONArray("route")) ?: return null
            if (route.isEmpty() || route.last() != to) return null
            val from = if (j.has("from") && !j.isNull("from")) (cleanId(j.optString("from"), Protocol.MAX_ID) ?: return null) else null
            return Ack(id, to, route, from)
        }
    }
}

/** Id узла: после trim непустой и не длиннее [max], иначе null. */
internal fun cleanId(raw: String, max: Int): String? =
    raw.trim().takeIf { it.isNotEmpty() && it.length <= max }

/**
 * Список id узлов (hops, route): только строки-id, без повторов, не длиннее [Protocol.MAX_HOPS].
 * null — список негодный (не массив, мусор, повтор, слишком длинный).
 */
internal fun parseIdList(arr: JSONArray?): List<String>? {
    if (arr == null || arr.length() > Protocol.MAX_HOPS) return null
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val v = arr.opt(i) as? String ?: return null
        val id = cleanId(v, Protocol.MAX_ID) ?: return null
        if (id in out) return null
        out.add(id)
    }
    return out
}

/** Id группы: непустая строка до [Protocol.MAX_ID] символов (после trim), иначе null. */
fun validGroupId(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= Protocol.MAX_ID }

/** Ключ беседы в таблице messages (колонка peer) для группового чата — не пересекается с адресами Bluetooth. */
fun groupChatKey(groupId: String): String = "group:$groupId"

/** Обратное к [groupChatKey]: id группы по ключу беседы; для адреса Bluetooth (личный чат) — null. */
fun groupIdFromKey(key: String?): String? =
    key?.takeIf { it.startsWith("group:") }?.substring("group:".length)?.takeIf { it.isNotEmpty() }

/** Участник группы: id установки и имя (имя может быть пустым). */
data class GroupMember(val installId: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", installId).put("name", name)

    companion object {
        /** Разбор списка: мусор пропускается, повторы по id отбрасываются, не больше [Protocol.MAX_GROUP_MEMBERS]. */
        fun listFromJson(arr: JSONArray?): List<GroupMember> {
            if (arr == null) return emptyList()
            val out = LinkedHashMap<String, GroupMember>()
            for (i in 0 until arr.length()) {
                if (out.size >= Protocol.MAX_GROUP_MEMBERS) break
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty() || id.length > Protocol.MAX_ID || id in out) continue
                out[id] = GroupMember(id, o.optString("name").cleanLine(Protocol.MAX_NAME))
            }
            return out.values.toList()
        }
    }
}

private fun List<GroupMember>.toJsonArray(): JSONArray =
    JSONArray().also { arr -> take(Protocol.MAX_GROUP_MEMBERS).forEach { arr.put(it.toJson()) } }

/** Группа в том виде, в каком хранится в базе. [rev] растёт при каждом изменении состава. */
data class GroupInfo(
    val id: String,
    val name: String,
    val rev: Long,
    val createdAt: Long,
    val unread: Int,
    val members: List<GroupMember>
) {
    val chatKey: String get() = groupChatKey(id)
}

/** Тип сообщения. Раньше гео определялось по префиксу «GEO:» в тексте — теперь это отдельное поле. */
enum class Kind(val code: String) {
    TEXT("text"), GEO("geo"), SOS("sos"),

    /**
     * Голосовое сообщение. Только в истории (звук лежит в [VoiceStore], в text — длина в мс). По проводу
     * идёт не кадром msg, а кадрами voice_*, поэтому из msg этот тип не принимается (см. [WireMessage.fromJson]).
     */
    VOICE("voice");

    companion object {
        fun of(code: String?): Kind? = values().firstOrNull { it.code == code }
    }
}

enum class Status(val code: Int) {
    /** Сохранено, ещё не ушло в канал (нет связи). */
    PENDING(0),
    /** Записано в канал. */
    SENT(1),
    /** Собеседник подтвердил получение (ACK). */
    DELIVERED(2),
    /** Входящее сообщение. */
    RECEIVED(3);

    companion object {
        fun of(code: Int): Status = values().firstOrNull { it.code == code } ?: PENDING
    }
}

/**
 * Точка геопозиции.
 * [fixTime] — момент замера по часам отправителя (мс), [accuracy] — радиус точности в метрах.
 */
data class GeoPoint(val lat: Double, val lon: Double, val accuracy: Float?, val fixTime: Long) {
    fun isValid(): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
}

/** Сообщение в том виде, в каком оно идёт по каналу. */
data class WireMessage(
    val id: String,
    val sentAt: Long,
    val kind: Kind,
    val text: String,
    val geo: GeoPoint?,
    /** Id группы; null — личное сообщение. */
    val groupId: String? = null,
    /** Id установки автора; null — прямой кадр (старый формат, пересылки не было). */
    val origin: String? = null,
    /** Получатель: id установки или "group:<id>"; null — не указан (для прямого кадра — собеседник по каналу). */
    val dest: String? = null,
    /** Сколько раз кадр ещё можно переслать. У прямого кадра 0. */
    val ttl: Int = 0,
    /** Id узлов, через которые кадр прошёл (первый — автор). У прямого кадра пусто. */
    val hops: List<String> = emptyList()
) {
    /** Прямой кадр: пришёл от автора по одному каналу, без пересылки. */
    val isDirect: Boolean get() = origin == null

    fun toJson(): JSONObject {
        val j = JSONObject()
            .put("id", id)
            .put("ts", sentAt)
            .put("k", kind.code)
            .put("text", text)
        if (groupId != null) j.put("g", groupId)
        // Поля пересылки пишем только у пересылаемых кадров: прямой кадр байт в байт как раньше.
        if (origin != null) {
            j.put("origin", origin).put("ttl", ttl)
            if (hops.isNotEmpty()) j.put("hops", JSONArray(hops))
        }
        if (dest != null) j.put("dest", dest)
        if (geo != null) {
            j.put("lat", geo.lat).put("lon", geo.lon).put("fix", geo.fixTime)
            val acc = geo.accuracy
            if (acc != null && acc.isFinite()) j.put("acc", acc.toDouble())
        }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): WireMessage? {
            val id = j.optString("id").trim()
            if (id.isEmpty() || id.length > 64) return null
            // Нет поля (или null) — личное сообщение. Поле есть, но id негодный — кадр отбрасываем,
            // чтобы сообщение группы не попало в личный чат.
            val groupId = if (j.has("g") && !j.isNull("g")) (validGroupId(j.optString("g")) ?: return null) else null
            // Поля пересылки: нет поля — прямой кадр. Негодное значение — кадр отбрасываем
            // (обрезать нельзя: по неполному списку hops не найти петлю).
            val origin = if (j.has("origin") && !j.isNull("origin")) (cleanId(j.optString("origin"), Protocol.MAX_ID) ?: return null) else null
            val dest = if (j.has("dest") && !j.isNull("dest")) (cleanId(j.optString("dest"), Protocol.MAX_DEST) ?: return null) else null
            val hops = parseHops(j) ?: return null
            // Старый кадр без origin: ttl и hops из него не берём, он остаётся прямым.
            val ttl = if (origin != null) j.optInt("ttl", 0).coerceIn(0, Protocol.MAX_TTL) else 0
            // голос по msg не ходит: «k":"voice"» из чужого кадра считаем обычным текстом
            val kind = Kind.of(j.optString("k"))?.takeIf { it != Kind.VOICE } ?: Kind.TEXT
            val text = j.optString("text").take(Protocol.MAX_TEXT)
            val geo = if (j.has("lat") && j.has("lon")) {
                val acc = if (j.has("acc")) j.optDouble("acc").toFloat() else null
                GeoPoint(
                    lat = j.optDouble("lat"),
                    lon = j.optDouble("lon"),
                    accuracy = acc?.takeIf { it.isFinite() && it >= 0f },
                    fixTime = j.optLong("fix", j.optLong("ts"))
                ).takeIf { it.isValid() }
            } else null
            if (kind == Kind.GEO && geo == null) return null
            if (kind == Kind.TEXT && text.isBlank()) return null
            return WireMessage(
                id, j.optLong("ts", System.currentTimeMillis()), kind, text, geo, groupId,
                origin, dest, ttl, if (origin != null) hops else emptyList()
            )
        }

        /** Список hops: нет поля — пусто. null — список негодный (см. [parseIdList]). */
        private fun parseHops(j: JSONObject): List<String>? =
            if (!j.has("hops") || j.isNull("hops")) emptyList() else parseIdList(j.optJSONArray("hops"))
    }
}

/** Сообщение в истории переписки. */
data class ChatMessage(
    val id: String,
    val peer: String,
    val fromMe: Boolean,
    val kind: Kind,
    val text: String,
    val geo: GeoPoint?,
    val sentAt: Long,
    val receivedAt: Long,
    val status: Status,
    /** Id группы; null — личное сообщение. Для группового сообщения [peer] = [groupChatKey]. */
    val groupId: String? = null,
    /** Входящее групповое сообщение: id установки автора (кто из участников написал). Иначе null. */
    val senderId: String? = null,
    /** Входящее: сколько посредников переслало сообщение (0 — пришло напрямую). Для пометки «через N». */
    val via: Int = 0
) {
    fun toWire() = WireMessage(id, sentAt, kind, text, geo, groupId)
}

/** Собеседник из истории (для списка чатов). */
data class PeerSummary(
    val address: String,
    val name: String,
    val role: String,
    val deviceName: String,
    val unread: Int,
    val lastKind: Kind?,
    val lastText: String?,
    val lastFromMe: Boolean,
    val lastTime: Long,
    /** Номер последнего сообщения в базе (0 — сообщений нет): по нему чаты и группы сортируются вместе. */
    val lastSeq: Long = 0
) {
    val title: String get() = displayName(name, deviceName, address)
}

/** Группа в списке чатов: сама группа и её последнее сообщение. */
data class GroupSummary(
    val info: GroupInfo,
    val lastKind: Kind?,
    val lastText: String?,
    val lastFromMe: Boolean,
    /** Имя автора последнего входящего сообщения (пусто, если неизвестно или сообщение своё). */
    val lastAuthor: String,
    val lastTime: Long,
    val lastSeq: Long
)

data class PeerRecord(
    val address: String,
    val installId: String,
    val name: String,
    val role: String,
    val deviceName: String
) {
    val title: String get() = displayName(name, deviceName, address)
}

fun displayName(name: String, deviceName: String, address: String): String =
    name.ifBlank { deviceName.ifBlank { address } }

/** Одна строка без управляющих символов, не длиннее [max]. */
fun String.cleanLine(max: Int): String =
    filter { !it.isISOControl() }.trim().take(max)
