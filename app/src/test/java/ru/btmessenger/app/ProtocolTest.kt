package ru.btmessenger.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Проверка протокола: кадры кодируются и разбираются без потерь, мусор отбрасывается. */
class ProtocolTest {

    private fun roundTrip(vararg frames: Frame): List<Frame> {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        frames.forEach { Protocol.write(out, it) }
        val inp = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        return frames.map { Protocol.read(inp) }
    }

    private fun readRaw(bytes: ByteArray): Frame = Protocol.read(DataInputStream(ByteArrayInputStream(bytes)))

    /** Кадр «как с провода»: 4 байта длины + JSON, чтобы проверять и разбор в Protocol.read. */
    private fun rawFrame(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply { writeInt(body.size); write(body) }
        return bos.toByteArray()
    }

    private fun parse(json: String): Frame = readRaw(rawFrame(json))

    private fun parseMsg(json: String): WireMessage = (parse(json) as Frame.Msg).message

    @Test
    fun allFrameTypesSurviveRoundTrip() {
        val hello = Frame.Hello(1, "id-123", "Иван Петров", "мастер участка")
        val text = WireMessage("m1", 1_700_000_000_500L, Kind.TEXT, "Привет 👋\nвторая строка", null)
        val sos = WireMessage("m3", 1_700_000_002_000L, Kind.SOS, "Нужна помощь!", null)
        val back = roundTrip(
            hello, Frame.Accept, Frame.Reject("busy"), Frame.Msg(text), Frame.Msg(sos),
            Frame.Ack("m1"), Frame.Ping, Frame.Pong, Frame.Bye
        )
        assertEquals(hello, back[0])
        assertSame(Frame.Accept, back[1])
        assertEquals(Frame.Reject("busy"), back[2])
        assertEquals(Frame.Msg(text), back[3])
        assertEquals(Frame.Msg(sos), back[4])
        assertEquals(Frame.Ack("m1"), back[5])
        assertSame(Frame.Ping, back[6])
        assertSame(Frame.Pong, back[7])
        assertSame(Frame.Bye, back[8])
    }

    @Test
    fun geoKeepsAccuracyAndFixTime() {
        val geo = GeoPoint(56.838011, 60.597474, 12.5f, 1_700_000_000_000L)
        val msg = WireMessage("m2", 1_700_000_001_000L, Kind.GEO, "", geo)
        val back = (roundTrip(Frame.Msg(msg))[0] as Frame.Msg).message
        assertEquals(Kind.GEO, back.kind)
        assertEquals(geo.lat, back.geo!!.lat, 1e-9)
        assertEquals(geo.lon, back.geo!!.lon, 1e-9)
        assertEquals(12.5f, back.geo!!.accuracy!!, 0.001f)
        assertEquals(geo.fixTime, back.geo!!.fixTime)
    }

    @Test
    fun textLookingLikeOldGeoPrefixStaysText() {
        val fake = WireMessage("m4", 1L, Kind.TEXT, "GEO:55.7,37.6", null)
        val back = (roundTrip(Frame.Msg(fake))[0] as Frame.Msg).message
        assertEquals(Kind.TEXT, back.kind)
        assertEquals(null, back.geo)
    }

    @Test
    fun badInputIsRejected() {
        assertTrue(Frame.fromJson(JSONObject("{\"t\":\"voice\"}")) is Frame.Unknown)
        assertTrue(Frame.fromJson(JSONObject("{\"t\":\"msg\",\"id\":\"x\",\"k\":\"geo\",\"lat\":500,\"lon\":10}")) is Frame.Unknown)
        assertTrue(Frame.fromJson(JSONObject("{\"t\":\"msg\",\"k\":\"text\",\"text\":\"hi\"}")) is Frame.Unknown)
        val hello = Frame.fromJson(JSONObject("{\"t\":\"hello\",\"id\":\"a\",\"name\":\"Ив\\u0000ан\\nX\"}")) as Frame.Hello
        assertEquals("ИванX", hello.name)
    }

    @Test(expected = IOException::class)
    fun oversizedFrameIsRejected() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).writeInt(10_000_000)
        readRaw(bos.toByteArray())
    }

    @Test(expected = IOException::class)
    fun oldWriteUtfFormatIsRejected() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).writeUTF("привет")
        readRaw(bos.toByteArray())
    }

    // ---------- MSG ----------

    @Test
    fun msgIgnoresUnknownFields() {
        val m = parseMsg(
            """{"t":"msg","id":"m1","ts":1000,"k":"text","text":"привет",
               "future":{"a":1},"list":[1,2,3],"flag":true,"v2":"x"}"""
        )
        assertEquals(WireMessage("m1", 1000L, Kind.TEXT, "привет", null), m)
    }

    @Test
    fun msgGeoWithUnknownFieldsAndOptionalParts() {
        val m = parseMsg("""{"t":"msg","id":"g1","ts":1000,"k":"geo","text":"","lat":56.83,"lon":60.59,"acc":8.5,"fix":900,"foo":"bar"}""")
        assertEquals(WireMessage("g1", 1000L, Kind.GEO, "", GeoPoint(56.83, 60.59, 8.5f, 900L)), m)

        // без точности и времени замера: точность неизвестна, время замера = время отправки
        val bare = parseMsg("""{"t":"msg","id":"g2","ts":1234,"k":"geo","lat":56.83,"lon":60.59}""")
        assertNull(bare.geo!!.accuracy)
        assertEquals(1234L, bare.geo!!.fixTime)

        // отрицательная точность — мусор, считаем неизвестной
        val neg = parseMsg("""{"t":"msg","id":"g3","ts":1,"k":"geo","lat":56.83,"lon":60.59,"acc":-5}""")
        assertNull(neg.geo!!.accuracy)
    }

    @Test
    fun msgSosKeepsTextAndGeo() {
        val m = parseMsg("""{"t":"msg","id":"s1","ts":7,"k":"sos","text":"Помогите","lat":10.5,"lon":20.5,"extra":1}""")
        assertEquals(Kind.SOS, m.kind)
        assertEquals("Помогите", m.text)
        assertEquals(10.5, m.geo!!.lat, 1e-9)
    }

    @Test
    fun msgUnknownKindIsHandledSafely() {
        // неизвестный тип с текстом показываем как обычный текст, без текста — игнорируем
        assertEquals(Kind.TEXT, parseMsg("""{"t":"msg","id":"k1","ts":1,"k":"voice","text":"hi"}""").kind)
        assertTrue(parse("""{"t":"msg","id":"k2","ts":1,"k":"voice"}""") is Frame.Unknown)
    }

    @Test
    fun msgWithoutValidIdOrBodyIsUnknown() {
        assertTrue(parse("""{"t":"msg","k":"text","text":"hi"}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"msg","id":"   ","k":"text","text":"hi"}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"msg","id":"${"x".repeat(65)}","k":"text","text":"hi"}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"msg","id":"m","k":"text","text":"   "}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"msg","id":"m","k":"geo","lat":0,"lon":0}""") is Frame.Unknown)
    }

    @Test
    fun msgIdIsTrimmedAndTextIsLimited() {
        val m = parseMsg("""{"t":"msg","id":"  m9  ","ts":1,"k":"text","text":"${"я".repeat(Protocol.MAX_TEXT + 100)}"}""")
        assertEquals("m9", m.id)
        assertEquals(Protocol.MAX_TEXT, m.text.length)
    }

    @Test
    fun msgWithoutTimestampGetsReceiveTime() {
        val before = System.currentTimeMillis()
        val m = parseMsg("""{"t":"msg","id":"t1","k":"text","text":"hi"}""")
        assertTrue(m.sentAt in before..System.currentTimeMillis())
    }

    // ---------- ACK ----------

    @Test
    fun ackIgnoresUnknownFields() {
        assertEquals(Frame.Ack("m1"), parse("""{"t":"ack","id":"m1","future":{"x":1},"seen":true}"""))
    }

    @Test
    fun ackWithoutIdMatchesNothing() {
        // кадр разбирается, но id пустой — ни одно сообщение с таким id не найдётся
        assertEquals(Frame.Ack(""), parse("""{"t":"ack"}"""))
    }

    @Test
    fun ackIdIsLimitedTo64() {
        val ack = parse("""{"t":"ack","id":"${"z".repeat(100)}"}""") as Frame.Ack
        assertEquals(64, ack.id.length)
    }

    @Test
    fun ackCarriesTheSameIdAsMsg() {
        // получатель подтверждает именно тот id, который прочитал из MSG
        val msg = parseMsg("""{"t":"msg","id":"abc-1","ts":1,"k":"text","text":"hi"}""")
        assertEquals(Frame.Ack("abc-1"), roundTrip(Frame.Ack(msg.id))[0])
    }

    // ---------- Поток кадров ----------

    @Test
    fun framesKeepOrderAndUnknownFramesAreSkipped() {
        // очередь уходит в том порядке, в каком сохранена; неизвестный кадр посреди потока не ломает чтение
        val m1 = WireMessage("m1", 1L, Kind.TEXT, "один", null)
        val m2 = WireMessage("m2", 2L, Kind.TEXT, "два", null)
        val m3 = WireMessage("m3", 3L, Kind.TEXT, "три", null)
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        Protocol.write(out, Frame.Msg(m1))
        out.write(rawFrame("""{"t":"typing","who":"x"}"""))
        Protocol.write(out, Frame.Msg(m2))
        Protocol.write(out, Frame.Msg(m3))
        val inp = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        val got = List(4) { Protocol.read(inp) }
        assertEquals(Frame.Msg(m1), got[0])
        assertEquals(Frame.Unknown("typing"), got[1])
        assertEquals(Frame.Msg(m2), got[2])
        assertEquals(Frame.Msg(m3), got[3])
    }

    // ---------- Группы ----------

    @Test
    fun msgWithoutGroupIdIsPrivate() {
        assertNull(parseMsg("""{"t":"msg","id":"p1","ts":1,"k":"text","text":"привет"}""").groupId)
        // "g": null тоже значит «нет поля»
        assertNull(parseMsg("""{"t":"msg","id":"p2","ts":1,"k":"text","text":"привет","g":null}""").groupId)
        // старый кадр без groupId не содержит поля "g" и после записи
        assertTrue(!WireMessage("p3", 1L, Kind.TEXT, "hi", null).toJson().has("g"))
    }

    @Test
    fun msgGroupIdSurvivesRoundTrip() {
        val text = WireMessage("g1", 5L, Kind.TEXT, "всем привет", null, "grp-1")
        val sos = WireMessage("g2", 6L, Kind.SOS, "SOS", GeoPoint(56.8, 60.6, 5f, 6L), "grp-1")
        val back = roundTrip(Frame.Msg(text), Frame.Msg(sos))
        assertEquals(Frame.Msg(text), back[0])
        assertEquals(Frame.Msg(sos), back[1])
        assertEquals("grp-1", parseMsg("""{"t":"msg","id":"g3","ts":1,"k":"text","text":"hi","g":"  grp-1 "}""").groupId)
    }

    @Test
    fun msgWithBadGroupIdIsDroppedNotShownAsPrivate() {
        assertTrue(parse("""{"t":"msg","id":"b1","ts":1,"k":"text","text":"hi","g":""}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"msg","id":"b2","ts":1,"k":"text","text":"hi","g":"   "}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"msg","id":"b3","ts":1,"k":"text","text":"hi","g":"${"x".repeat(65)}"}""") is Frame.Unknown)
    }

    @Test
    fun groupFramesSurviveRoundTrip() {
        val members = listOf(GroupMember("id-1", "Иван"), GroupMember("id-2", "Мария"), GroupMember("id-3", ""))
        val invite = Frame.GroupInvite("grp-1", "Бригада №1", members)
        val join = Frame.GroupJoin("grp-1", GroupMember("id-2", "Мария"))
        val leave = Frame.GroupLeave("grp-1", "id-2")
        val list = Frame.GroupMembers("grp-1", 7L, members)
        val back = roundTrip(invite, join, leave, list)
        assertEquals(invite, back[0])
        assertEquals(join, back[1])
        assertEquals(leave, back[2])
        assertEquals(list, back[3])
        assertTrue(back.all { it is Frame.GroupFrame })
    }

    @Test
    fun groupInviteParsesFieldsAndIgnoresUnknown() {
        val f = parse(
            """{"t":"group_invite","g":" grp-9 ","gname":"Ночная\nсмена","future":{"a":1},
               "members":[{"id":"a","name":"Аня","x":1},{"id":"b","name":"Борис"}]}"""
        ) as Frame.GroupInvite
        assertEquals("grp-9", f.groupId)
        assertEquals("Ночнаясмена", f.name)
        assertEquals(listOf(GroupMember("a", "Аня"), GroupMember("b", "Борис")), f.members)
    }

    @Test
    fun groupMembersListIsCleanedAndLimited() {
        val raw = buildString {
            append("""{"t":"group_members","g":"g","rev":3,"members":[""")
            append("""5,"text",null,{"name":"без id"},{"id":"  "},{"id":"${"x".repeat(65)}"},""")
            append("""{"id":"a","name":"Первый"},{"id":"a","name":"Повтор"},""")
            for (i in 0 until 50) append("""{"id":"u$i","name":"n$i"},""")
            append("""{"id":"z"}]}""")
        }
        val f = parse(raw) as Frame.GroupMembers
        assertEquals(3L, f.rev)
        assertEquals(Protocol.MAX_GROUP_MEMBERS, f.members.size)
        assertEquals(GroupMember("a", "Первый"), f.members[0]) // повтор по id отброшен
        assertEquals("u0", f.members[1].installId)
    }

    @Test
    fun groupMembersDefaultsAndMissingList() {
        val f = parse("""{"t":"group_members","g":"g"}""") as Frame.GroupMembers
        assertEquals(0L, f.rev)
        assertTrue(f.members.isEmpty())
        assertEquals(0L, (parse("""{"t":"group_members","g":"g","rev":-5}""") as Frame.GroupMembers).rev)
    }

    @Test
    fun groupFramesWithoutRequiredFieldsAreUnknown() {
        assertEquals(Frame.Unknown("group_invite"), parse("""{"t":"group_invite","gname":"x"}"""))
        assertEquals(Frame.Unknown("group_members"), parse("""{"t":"group_members","g":"  "}"""))
        assertEquals(Frame.Unknown("group_join"), parse("""{"t":"group_join","g":"g"}"""))
        assertEquals(Frame.Unknown("group_join"), parse("""{"t":"group_join","id":"a"}"""))
        assertEquals(Frame.Unknown("group_leave"), parse("""{"t":"group_leave","g":"g","id":" "}"""))
        assertEquals(Frame.Unknown("group_leave"), parse("""{"t":"group_leave","id":"a"}"""))
        assertEquals(Frame.Unknown("group_leave"), parse("""{"t":"group_leave","g":"${"g".repeat(65)}","id":"a"}"""))
    }

    @Test
    fun groupJoinAndLeaveParse() {
        assertEquals(
            Frame.GroupJoin("g", GroupMember("a", "Анна")),
            parse("""{"t":"group_join","g":"g","id":" a ","name":"Ан\u0000на","extra":true}""")
        )
        assertEquals(Frame.GroupLeave("g", "a"), parse("""{"t":"group_leave","g":"g","id":"a","why":"kick"}"""))
    }

    @Test
    fun groupFramesDoNotBreakOldStream() {
        // поток со старыми кадрами и группами вперемешку читается подряд, порядок сохраняется
        val m = WireMessage("m1", 1L, Kind.TEXT, "личное", null)
        val back = roundTrip(Frame.Msg(m), Frame.GroupLeave("g", "a"), Frame.Ack("m1"))
        assertEquals(Frame.Msg(m), back[0])
        assertEquals(Frame.GroupLeave("g", "a"), back[1])
        assertEquals(Frame.Ack("m1"), back[2])
    }

    @Test
    fun groupHelpers() {
        assertEquals("group:abc", groupChatKey("abc"))
        assertNull(validGroupId(null))
        assertNull(validGroupId("  "))
        assertEquals("a", validGroupId(" a "))
    }

    @Test
    fun groupIdFromKeyIsInverseOfChatKey() {
        assertEquals("abc", groupIdFromKey(groupChatKey("abc")))
        // адрес Bluetooth (личный чат), пустой ключ и ключ без id — не группа
        assertNull(groupIdFromKey("AA:BB:CC:DD:EE:FF"))
        assertNull(groupIdFromKey(null))
        assertNull(groupIdFromKey("group:"))
    }
}
