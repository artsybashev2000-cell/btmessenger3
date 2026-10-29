package ru.btmessenger.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Правила пересылки: ttl, петли, повторы, размер hops и совместимость со старыми кадрами. */
class MeshRulesTest {

    private val base = WireMessage("m1", 1L, Kind.TEXT, "привет", null)

    private fun wire(m: WireMessage): Frame {
        val bos = ByteArrayOutputStream()
        Protocol.write(DataOutputStream(bos), Frame.Msg(m))
        return Protocol.read(DataInputStream(ByteArrayInputStream(bos.toByteArray())))
    }

    private fun parse(json: String): Frame = Frame.fromJson(JSONObject(json))

    // ---------- Формат ----------

    @Test
    fun originateSetsFields() {
        val m = MeshRules.originate(base, "A", "C")
        assertEquals("A", m.origin)
        assertEquals("C", m.dest)
        assertEquals(Protocol.MAX_TTL, m.ttl)
        assertEquals(listOf("A"), m.hops)
        assertFalse(m.isDirect)
    }

    @Test
    fun meshFieldsSurviveRoundTrip() {
        val m = MeshRules.originate(base, "A", MeshRules.groupDest("grp-1")).copy(ttl = 2, hops = listOf("A", "B"))
        assertEquals(Frame.Msg(m), wire(m))
        assertEquals("grp-1", MeshRules.groupIdOfDest(m.dest))
    }

    @Test
    fun oldFrameWithoutMeshFieldsIsDirect() {
        val m = (parse("""{"t":"msg","id":"m1","ts":1,"k":"text","text":"hi"}""") as Frame.Msg).message
        assertTrue(m.isDirect)
        assertNull(m.origin)
        assertNull(m.dest)
        assertEquals(0, m.ttl)
        assertTrue(m.hops.isEmpty())
        assertEquals(MeshRules.Verdict.DIRECT, MeshRules.verdict(m, "B"))
        assertNull(MeshRules.forwardCopy(m, "B"))
    }

    @Test
    fun directFrameKeepsOldBytes() {
        val j = base.toJson()
        assertFalse(j.has("origin"))
        assertFalse(j.has("dest"))
        assertFalse(j.has("ttl"))
        assertFalse(j.has("hops"))
    }

    @Test
    fun ttlAndHopsWithoutOriginAreIgnored() {
        val m = (parse("""{"t":"msg","id":"m1","k":"text","text":"hi","ttl":3,"hops":["X"]}""") as Frame.Msg).message
        assertTrue(m.isDirect)
        assertEquals(0, m.ttl)
        assertTrue(m.hops.isEmpty())
    }

    @Test
    fun ttlIsClampedToMax() {
        val big = (parse("""{"t":"msg","id":"m","k":"text","text":"hi","origin":"A","ttl":99,"hops":["A"]}""") as Frame.Msg).message
        assertEquals(Protocol.MAX_TTL, big.ttl)
        val neg = (parse("""{"t":"msg","id":"m","k":"text","text":"hi","origin":"A","ttl":-4,"hops":["A"]}""") as Frame.Msg).message
        assertEquals(0, neg.ttl)
    }

    @Test
    fun badMeshFieldsDropTheFrame() {
        val head = """{"t":"msg","id":"m","k":"text","text":"hi","""
        assertTrue(parse(head + """"origin":""}""") is Frame.Unknown)
        assertTrue(parse(head + """"origin":"${"x".repeat(65)}"}""") is Frame.Unknown)
        assertTrue(parse(head + """"origin":"A","dest":"${"x".repeat(Protocol.MAX_DEST + 1)}"}""") is Frame.Unknown)
        assertTrue(parse(head + """"origin":"A","hops":"A"}""") is Frame.Unknown)
        assertTrue(parse(head + """"origin":"A","hops":["A",5]}""") is Frame.Unknown)
        assertTrue(parse(head + """"origin":"A","hops":["A",""]}""") is Frame.Unknown)
        // повтор id в hops: такой кадр уже ходил по кругу
        assertTrue(parse(head + """"origin":"A","hops":["A","B","A"]}""") is Frame.Unknown)
    }

    @Test
    fun hopsLongerThanMaxIsDropped() {
        val ok = (0 until Protocol.MAX_HOPS).joinToString(",") { "\"n$it\"" }
        val long = (0..Protocol.MAX_HOPS).joinToString(",") { "\"n$it\"" }
        assertTrue(parse("""{"t":"msg","id":"m","k":"text","text":"hi","origin":"n0","ttl":0,"hops":[$ok]}""") is Frame.Msg)
        assertTrue(parse("""{"t":"msg","id":"m","k":"text","text":"hi","origin":"n0","ttl":0,"hops":[$long]}""") is Frame.Unknown)
    }

    @Test
    fun maxFrameWithLongestIdsFitsLimit() {
        val ids = List(Protocol.MAX_HOPS) { i -> i.toString().padEnd(Protocol.MAX_ID, 'x') }
        val m = base.copy(text = "я".repeat(Protocol.MAX_TEXT), origin = ids[0], dest = "d".repeat(Protocol.MAX_DEST), ttl = 0, hops = ids)
        assertEquals(Frame.Msg(m), wire(m))
    }

    // ---------- ttl ----------

    @Test
    fun forwardLowersTtlAndAppendsMyId() {
        val a = MeshRules.originate(base, "A", "C")
        val b = MeshRules.forwardCopy(a, "B")!!
        assertEquals(Protocol.MAX_TTL - 1, b.ttl)
        assertEquals(listOf("A", "B"), b.hops)
        assertEquals("A", b.origin)
        assertEquals("C", b.dest)
        assertEquals(a.id, b.id)
    }

    @Test
    fun frameWithTtlZeroIsNotForwarded() {
        val m = MeshRules.originate(base, "A", "C").copy(ttl = 0)
        assertEquals(MeshRules.Verdict.TTL_EXPIRED, MeshRules.verdict(m, "B"))
        assertNull(MeshRules.forwardCopy(m, "B"))
    }

    @Test
    fun chainStopsAfterMaxTtlForwards() {
        var m = MeshRules.originate(base, "n0", "dest")
        var forwards = 0
        var i = 1
        while (true) {
            m = MeshRules.forwardCopy(m, "n$i") ?: break
            forwards++
            i++
        }
        assertEquals(Protocol.MAX_TTL, forwards)
        assertEquals(0, m.ttl)
        // у последнего узла hops ровно MAX_HOPS и такой кадр ещё проходит разбор
        assertEquals(Protocol.MAX_HOPS, m.hops.size)
        assertEquals(Frame.Msg(m), wire(m))
    }

    @Test
    fun hopsFullBlocksForwardEvenIfTtlLeft() {
        val hops = List(Protocol.MAX_HOPS) { "n$it" }
        val m = base.copy(origin = "n0", ttl = 2, hops = hops)
        assertEquals(MeshRules.Verdict.HOPS_FULL, MeshRules.verdict(m, "me"))
        assertNull(MeshRules.forwardCopy(m, "me"))
    }

    // ---------- Зацикливание ----------

    @Test
    fun frameWithMyIdInHopsIsDropped() {
        val m = MeshRules.originate(base, "A", "C").copy(ttl = 2, hops = listOf("A", "B"))
        assertTrue(MeshRules.isLoop(m, "B"))
        assertEquals(MeshRules.Verdict.LOOP, MeshRules.verdict(m, "B"))
        assertNull(MeshRules.forwardCopy(m, "B"))
    }

    @Test
    fun myOwnFrameComingBackIsDropped() {
        // автор тоже в hops, но проверяем и случай с кадром, где hops пуст (чужая реализация)
        val m = base.copy(origin = "A", ttl = 2, hops = emptyList())
        assertEquals(MeshRules.Verdict.LOOP, MeshRules.verdict(m, "A"))
    }

    @Test
    fun ringOfThreeNodesStopsAfterOneLap() {
        // A-B-C-A: каждый узел пересылает соседям, кадр не должен идти по кругу
        val nodes = listOf("A", "B", "C")
        var delivered = 0
        fun visit(m: WireMessage, at: String) {
            if (MeshRules.isLoop(m, at)) return
            delivered++
            val next = MeshRules.forwardCopy(m, at) ?: return
            nodes.filter { it != at }.forEach { visit(next, it) }
        }
        val first = MeshRules.originate(base, "A", null)
        // A отправляет B и C как прямым соседям
        listOf("B", "C").forEach { visit(first, it) }
        // Принятых кадров четыре: A->B, B->C, A->C, C->B. Возвраты к A и повторы у B и C отброшены как петли.
        assertEquals(4, delivered)
    }

    @Test
    fun framesNeverRepeatNodeInHops() {
        // полный граф из 5 узлов: ни один кадр не содержит повторов в hops, глубина не больше MAX_HOPS
        val nodes = List(5) { "n$it" }
        var maxDepth = 0
        fun visit(m: WireMessage, at: String) {
            assertEquals(m.hops.size, m.hops.toSet().size)
            maxDepth = maxOf(maxDepth, m.hops.size)
            val next = MeshRules.forwardCopy(m, at) ?: return
            nodes.filter { it != at }.forEach { visit(next, it) }
        }
        val first = MeshRules.originate(base, "n0", null)
        nodes.drop(1).forEach { visit(first, it) }
        assertTrue(maxDepth <= Protocol.MAX_HOPS)
    }

    // ---------- Повтор ----------

    @Test
    fun seenCacheDropsRepeats() {
        val c = SeenCache()
        assertTrue(c.markNew("A", "m1"))
        assertFalse(c.markNew("A", "m1"))
        // тот же id от другого автора — другое сообщение
        assertTrue(c.markNew("B", "m1"))
        assertTrue(c.markNew("A", "m2"))
    }

    @Test
    fun seenCacheEvictsOldest() {
        val c = SeenCache(capacity = 3)
        for (i in 1..4) assertTrue(c.markNew("A", "m$i"))
        assertEquals(3, c.size)
        // m1 вытеснен и снова считается новым, m4 ещё помнится
        assertFalse(c.markNew("A", "m4"))
        assertTrue(c.markNew("A", "m1"))
    }

    @Test
    fun sameFrameFromTwoPathsIsHandledOnce() {
        val a = MeshRules.originate(base, "A", "D")
        val viaB = MeshRules.forwardCopy(a, "B")!!
        val viaC = MeshRules.forwardCopy(a, "C")!!
        val seen = SeenCache()
        assertTrue(seen.markNew(viaB.origin!!, viaB.id))
        assertFalse(seen.markNew(viaC.origin!!, viaC.id))
    }

    // ---------- Часть 2: приём и пересылка ----------

    private fun inbound(
        m: WireMessage, me: String, sender: String,
        groups: Set<String> = emptySet(), foreign: Boolean = false
    ) = MeshRules.planInbound(m, me, sender, { it in groups }, foreign)

    @Test
    fun transitFrameIsForwardedAndNotDelivered() {
        val a = MeshRules.originate(base, "A", "C")
        val p = inbound(a, me = "B", sender = "A")
        assertFalse(p.deliver)
        assertEquals(MeshRules.Drop.NONE, p.drop)
        assertEquals(listOf("A", "B"), p.forward!!.hops)
        assertEquals(Protocol.MAX_TTL - 1, p.forward!!.ttl)
    }

    @Test
    fun frameForMeIsDeliveredAndNotForwarded() {
        val b = MeshRules.forwardCopy(MeshRules.originate(base, "A", "C"), "B")!!
        val p = inbound(b, me = "C", sender = "B")
        assertTrue(p.deliver)
        assertNull(p.forward)
    }

    @Test
    fun badPathIsDropped() {
        val a = MeshRules.originate(base, "A", "C")
        // кадр от соседа X, а в hops последний A: чужой кадр выдан за кадр соседа
        assertEquals(MeshRules.Drop.BAD_PATH, inbound(a, "B", "X").drop)
        assertEquals(MeshRules.Drop.BAD_PATH, inbound(a.copy(hops = emptyList()), "B", "A").drop)
        assertEquals(MeshRules.Drop.BAD_PATH, inbound(a.copy(hops = listOf("Z", "A")), "B", "A").drop)
        assertEquals(MeshRules.Drop.DIRECT, inbound(base, "B", "A").drop)
    }

    @Test
    fun loopAndMissingDestAreDropped() {
        val ab = MeshRules.forwardCopy(MeshRules.originate(base, "A", "C"), "B")!!
        assertEquals(MeshRules.Drop.LOOP, inbound(ab, "A", "B").drop)
        val noDest = MeshRules.originate(base, "A", null)
        assertEquals(MeshRules.Drop.NO_DEST, inbound(noDest, "B", "A").drop)
    }

    @Test
    fun groupAndDestMustAgree() {
        val g = base.copy(groupId = "g1")
        // группа в g, а dest — человек
        assertEquals(MeshRules.Drop.BAD_DEST, inbound(MeshRules.originate(g, "A", "C"), "B", "A").drop)
        // dest — группа, а g нет
        assertEquals(MeshRules.Drop.BAD_DEST, inbound(MeshRules.originate(base, "A", "group:g1"), "B", "A").drop)
        // разные группы
        assertEquals(MeshRules.Drop.BAD_DEST, inbound(MeshRules.originate(g, "A", "group:g2"), "B", "A").drop)
    }

    @Test
    fun foreignGroupIsRelayedOnlyIfAllowed() {
        val m = MeshRules.originate(base.copy(groupId = "g1"), "A", "group:g1")
        val off = inbound(m, "B", "A")
        assertFalse(off.deliver)
        assertNull(off.forward)
        val on = inbound(m, "B", "A", foreign = true)
        assertFalse(on.deliver)
        assertEquals(listOf("A", "B"), on.forward!!.hops)
    }

    @Test
    fun memberOfGroupDeliversAndForwards() {
        val m = MeshRules.originate(base.copy(groupId = "g1"), "A", "group:g1")
        val p = inbound(m, "B", "A", groups = setOf("g1"))
        assertTrue(p.deliver)
        assertEquals(listOf("A", "B"), p.forward!!.hops)
    }

    @Test
    fun forwardTargetsSkipSenderAndHops() {
        val m = base.copy(origin = "A", dest = "Z", ttl = 1, hops = listOf("A", "B", "ME"))
        assertEquals(listOf("D", "E"), MeshRules.forwardTargets(m, listOf("B", "A", "D", "E"), cameFrom = "B"))
    }

    @Test
    fun seenKeySeparatesGeoUpdates() {
        val sos = base.copy(kind = Kind.SOS, geo = GeoPoint(56.8, 60.6, 5f, 100L))
        val newer = sos.copy(geo = GeoPoint(56.9, 60.7, 3f, 200L))
        assertTrue(MeshRules.seenKey(sos) != MeshRules.seenKey(newer))
        assertEquals("m1", MeshRules.seenKey(base))
        val c = SeenCache()
        assertTrue(c.markNew("A", MeshRules.seenKey(sos)))
        assertTrue(c.markNew("A", MeshRules.seenKey(newer)))
        assertFalse(c.markNew("A", MeshRules.seenKey(newer)))
    }

    // ---------- ACK по пути hops ----------

    @Test
    fun ackWalksBackAlongHops() {
        val a = MeshRules.originate(base, "A", "C")
        val atB = MeshRules.forwardCopy(a, "B")!!
        val ack = MeshRules.relayedAck(atB, "C")
        assertEquals(listOf("B", "A"), ack.route)
        assertEquals("A", ack.to)
        assertEquals("C", ack.from)
        // B: ACK не ему адресован как автору, передаёт A
        val atBack = MeshRules.planAck(ack, "B")
        assertFalse(atBack.deliver)
        assertEquals("A", atBack.nextHop)
        assertEquals(listOf("A"), atBack.forward!!.route)
        // A: дошёл
        assertTrue(MeshRules.planAck(atBack.forward!!, "A").deliver)
    }

    @Test
    fun ackNotForMeIsDropped() {
        val ack = Frame.Ack("m1", to = "A", route = listOf("B", "A"), from = "C")
        val p = MeshRules.planAck(ack, "X")
        assertFalse(p.deliver)
        assertNull(p.forward)
        assertNull(MeshRules.planAck(Frame.Ack("m1"), "A").forward)
        // автор не я, а путь кончился на мне
        assertFalse(MeshRules.planAck(Frame.Ack("m1", "A", listOf("B"), "C"), "B").deliver)
    }

    @Test
    fun relayedAckSurvivesRoundTripAndPlainAckStaysPlain() {
        val ack = Frame.Ack("m1", "A", listOf("B", "A"), "C")
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        Protocol.write(out, ack)
        Protocol.write(out, Frame.Ack("m2"))
        val inp = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(ack, Protocol.read(inp))
        assertEquals(Frame.Ack("m2"), Protocol.read(inp))
        assertFalse(Frame.Ack("m2").toJson().has("to"))
    }

    @Test
    fun badRelayedAckIsNotTakenForPlain() {
        assertTrue(parse("""{"t":"ack","id":"m","to":"A","route":[]}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"ack","id":"m","to":"A","route":["B"]}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"ack","id":"m","to":"A"}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"ack","id":"m","to":"A","route":["B","B","A"]}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"ack","id":"m","to":"A","route":["B","A"],"from":""}""") is Frame.Unknown)
        assertTrue(parse("""{"t":"ack","id":"m","to":"A","route":["a","b","c","d","A"]}""") is Frame.Unknown)
    }

    // ---------- Сеть из нескольких телефонов ----------

    /** Игрушечная сеть: [edges] — кто с кем на связи. Каждый узел ведёт свою [SeenCache] и ходит по правилам. */
    private class Net(
        val edges: Map<String, List<String>>,
        val groups: Map<String, Set<String>> = emptyMap(),
        val foreign: Boolean = false,
        /** Узлы с выключенной пересылкой. */
        val relayOff: Set<String> = emptySet()
    ) {
        val seen = edges.keys.associateWith { SeenCache() }
        val delivered = mutableListOf<String>()
        val forwards = mutableMapOf<String, Int>()
        var transmissions = 0

        fun send(origin: String, m: WireMessage) {
            val q = ArrayDeque<Triple<String, String, WireMessage>>()
            edges.getValue(origin).forEach { q.add(Triple(origin, it, m)) }
            while (q.isNotEmpty()) {
                transmissions++
                assertTrue("кадр зациклился", transmissions < 1000)
                val (from, at, msg) = q.removeFirst()
                val plan = MeshRules.planInbound(msg, at, from, { groups[at]?.contains(it) == true }, foreign, relayEnabled = at !in relayOff)
                if (plan.drop != MeshRules.Drop.NONE) continue
                if (plan.deliver) delivered += at
                val fwd = plan.forward ?: continue
                if (!seen.getValue(at).markNew(msg.origin!!, MeshRules.seenKey(msg))) continue
                forwards[at] = (forwards[at] ?: 0) + 1
                MeshRules.forwardTargets(fwd, edges.getValue(at), from).forEach { q.add(Triple(at, it, fwd)) }
            }
        }
    }

    private fun line(vararg ids: String): Map<String, List<String>> =
        ids.indices.associate { i -> ids[i] to listOfNotNull(ids.getOrNull(i - 1), ids.getOrNull(i + 1)) }

    @Test
    fun messageReachesPhoneThroughMiddleOne() {
        val net = Net(line("A", "B", "C"))
        net.send("A", MeshRules.originate(base, "A", "C"))
        assertEquals(listOf("C"), net.delivered) // B ничего не получил «для себя», у A кадр не вернулся
        assertEquals(1, net.forwards["B"])
        assertNull(net.forwards["C"])
    }

    @Test
    fun diamondWithCrossLinkForwardsOncePerNode() {
        val net = Net(
            mapOf(
                "A" to listOf("B", "C"),
                "B" to listOf("A", "C", "D"),
                "C" to listOf("A", "B", "D"),
                "D" to listOf("B", "C")
            )
        )
        net.send("A", MeshRules.originate(base, "A", "D"))
        assertTrue(net.delivered.isNotEmpty())
        assertTrue(net.delivered.all { it == "D" })
        assertTrue(net.forwards.values.all { it <= 1 })
        assertTrue(net.forwards.keys.none { it == "A" || it == "D" })
    }

    @Test
    fun fullGraphOfFiveTerminates() {
        val ids = List(5) { "n$it" }
        val net = Net(ids.associateWith { me -> ids.filter { it != me } })
        net.send("n0", MeshRules.originate(base, "n0", "n4"))
        assertTrue(net.delivered.all { it == "n4" })
        assertTrue(net.forwards.values.all { it <= 1 })
    }

    @Test
    fun chainStopsAfterMaxTtl() {
        val ids = List(6) { "n$it" }
        val reach = Net(line(*ids.toTypedArray()))
        reach.send("n0", MeshRules.originate(base, "n0", "n4"))
        assertEquals(listOf("n4"), reach.delivered) // четыре перехода: доходит
        val far = Net(line(*ids.toTypedArray()))
        far.send("n0", MeshRules.originate(base, "n0", "n5"))
        assertTrue(far.delivered.isEmpty()) // пять переходов: ttl кончился
    }

    @Test
    fun groupMessageCrossesNonMemberOnlyIfAllowed() {
        val m = MeshRules.originate(base.copy(groupId = "g1"), "A", "group:g1")
        val members = mapOf("A" to setOf("g1"), "C" to setOf("g1"))
        val closed = Net(line("A", "B", "C"), groups = members)
        closed.send("A", m)
        assertTrue(closed.delivered.isEmpty())
        val open = Net(line("A", "B", "C"), groups = members, foreign = true)
        open.send("A", m)
        assertEquals(listOf("C"), open.delivered)
    }

    // ---------- Часть 3: настройка «Пересылать чужие сообщения» и пометка «через N» ----------

    @Test
    fun relayDisabledDoesNotForwardButStillDelivers() {
        val toC = MeshRules.originate(base, "A", "C")
        val transit = MeshRules.planInbound(toC, "B", "A", { false }, false, relayEnabled = false)
        assertFalse(transit.deliver)
        assertNull(transit.forward)
        assertEquals(MeshRules.Drop.NONE, transit.drop)
        // сообщение мне приходит как обычно
        val atC = MeshRules.forwardCopy(toC, "B")!!
        assertTrue(MeshRules.planInbound(atC, "C", "B", { false }, false, relayEnabled = false).deliver)
        // сообщение моей группы показываем, но дальше не пересылаем
        val grp = MeshRules.originate(base.copy(groupId = "g1"), "A", "group:g1")
        val p = MeshRules.planInbound(grp, "B", "A", { it == "g1" }, true, relayEnabled = false)
        assertTrue(p.deliver)
        assertNull(p.forward)
    }

    @Test
    fun relayDisabledDoesNotPassAckButAcceptsOwn() {
        val ack = Frame.Ack("m1", "A", listOf("B", "A"), "C")
        val transit = MeshRules.planAck(ack, "B", relayEnabled = false)
        assertFalse(transit.deliver)
        assertNull(transit.forward)
        assertNull(transit.nextHop)
        assertTrue(MeshRules.planAck(Frame.Ack("m1", "A", listOf("A"), "C"), "A", relayEnabled = false).deliver)
    }

    @Test
    fun relayOffMiddlePhoneBlocksTheChain() {
        val m = MeshRules.originate(base, "A", "C")
        val on = Net(line("A", "B", "C"))
        on.send("A", m)
        assertEquals(listOf("C"), on.delivered)
        val off = Net(line("A", "B", "C"), relayOff = setOf("B"))
        off.send("A", m)
        assertTrue(off.delivered.isEmpty())
        assertTrue(off.forwards.isEmpty())
    }

    @Test
    fun relayOffPhoneStillReceivesItsOwnMessages() {
        val off = Net(line("A", "B", "C"), relayOff = setOf("C"))
        off.send("A", MeshRules.originate(base, "A", "C"))
        assertEquals(listOf("C"), off.delivered)
    }

    @Test
    fun viaCountIsNumberOfIntermediaries() {
        val a = MeshRules.originate(base, "A", "D")
        assertEquals(0, MeshRules.viaCount(a))
        val atC = MeshRules.forwardCopy(MeshRules.forwardCopy(a, "B")!!, "C")!!
        // у D в hops [A, B, C]: посредников двое
        assertEquals(2, MeshRules.viaCount(atC))
        assertEquals(0, MeshRules.viaCount(base))
    }
}
