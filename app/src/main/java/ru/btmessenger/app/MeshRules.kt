package ru.btmessenger.app

/**
 * Правила пересылки по цепочке (часть 1: только правила, сети здесь нет).
 *
 * Идея: A не видит C, но видит B, а B видит C. B принимает сообщение A и отдаёт его C.
 *
 * Поля кадра MSG (см. [WireMessage]):
 *   origin — id установки автора. Нет поля — кадр прямой, пересылке не подлежит.
 *   dest   — id установки получателя или "group:<id группы>".
 *   ttl    — сколько раз кадр ещё можно переслать.
 *   hops   — id узлов, которые кадр уже прошли. Первый — автор, дальше каждый пересылающий.
 *
 * Правила:
 *   1. Автор ставит ttl = [Protocol.MAX_TTL] и hops = [автор] ([MeshRules.originate]).
 *   2. Кадр, где в hops уже есть мой id, отбрасывается (петля). Свой же кадр, вернувшийся ко мне, тоже.
 *   3. Кадр с ttl 0 не пересылается (но доставляется мне, если он мне адресован).
 *   4. При пересылке ttl уменьшается на 1, а мой id добавляется в конец hops.
 *   5. Кадр с id, который я уже видел от того же автора, не пересылается второй раз ([SeenCache]).
 *   6. Сообщение группы, в которой я не состою, пересылается только если это разрешено настройкой.
 *
 * Выбор MAX_TTL = 3. Это три пересылки, то есть до четырёх Bluetooth-переходов между автором и
 * дальним получателем. Один переход у телефонов даёт порядка 10 м в помещении, четыре перехода
 * покрывают здание или участок цеха. Каждая пересылка добавляет задержку (ACK, возможное
 * переподключение) и расходует радиоканал и батарею соседей, а при ttl больше 3 сообщение
 * доходит до узла, о котором автор вряд ли знает. В приложении у телефона не больше 4 соединений,
 * поэтому на каждом шаге кадр уходит всем соседям, и число копий растёт быстро.
 *
 * Размер hops: не больше [Protocol.MAX_HOPS] = MAX_TTL + 1 = 4 id (автор и три пересылающих).
 * Максимум в JSON: 4 id по 64 символа, это около 330 байт, кадр остаётся далеко от 64 КБ.
 * Кадр с более длинным hops, с повтором id в hops или с мусором вместо id при разборе отбрасывается
 * целиком: обрезать список нельзя, иначе петля перестанет находиться.
 */
object MeshRules {

    /** Причина, по которой кадр не пересылается или отбрасывается. */
    enum class Verdict {
        /** Кадр можно переслать дальше. */
        FORWARD,
        /** Прямой кадр (без origin): пересылке не подлежит. */
        DIRECT,
        /** ttl исчерпан. */
        TTL_EXPIRED,
        /** Мой id уже в hops или я автор: петля. */
        LOOP,
        /** hops длиннее допустимого. */
        HOPS_FULL
    }

    /** Первый кадр цепочки: от автора [myId]. [dest] — получатель или группа. */
    fun originate(base: WireMessage, myId: String, dest: String?): WireMessage =
        base.copy(origin = myId, dest = dest, ttl = Protocol.MAX_TTL, hops = listOf(myId))

    /** "group:<id>" для адресата-группы. */
    fun groupDest(groupId: String): String = groupChatKey(groupId)

    /** Id группы, если [dest] — адресат-группа, иначе null. */
    fun groupIdOfDest(dest: String?): String? = groupIdFromKey(dest)

    /** Петля: я уже в hops или я сам автор кадра. */
    fun isLoop(m: WireMessage, myId: String): Boolean =
        myId.isNotEmpty() && (myId in m.hops || m.origin == myId)

    /** Можно ли пересылать кадр дальше с моего узла [myId]. Проверки идут в порядке важности. */
    fun verdict(m: WireMessage, myId: String): Verdict = when {
        m.isDirect -> Verdict.DIRECT
        isLoop(m, myId) -> Verdict.LOOP
        m.ttl <= 0 -> Verdict.TTL_EXPIRED
        m.hops.size >= Protocol.MAX_HOPS -> Verdict.HOPS_FULL
        else -> Verdict.FORWARD
    }

    /** Кадр для отправки дальше: ttl - 1, мой id в конце hops. Null, если пересылать нельзя. */
    fun forwardCopy(m: WireMessage, myId: String): WireMessage? =
        if (verdict(m, myId) == Verdict.FORWARD) m.copy(ttl = m.ttl - 1, hops = m.hops + myId) else null

    // ---------- Часть 2: приём пересланного кадра ----------

    /** Почему кадр отброшен целиком (не доставляется и не пересылается). */
    enum class Drop {
        NONE,
        /** Прямой кадр: сюда он попасть не должен. */
        DIRECT,
        /** hops пуст, начинается не с автора или кончается не тем, кто прислал: путь не сходится. */
        BAD_PATH,
        /** Мой id уже в hops или я автор. */
        LOOP,
        /** Нет получателя: пересылать некуда. */
        NO_DEST,
        /** Получатель и поле группы противоречат друг другу (группа в dest, а g нет, или разные группы). */
        BAD_DEST
    }

    /**
     * Что делать с принятым пересланным кадром.
     * [deliver] — сообщение адресовано мне (лично или в мою группу): показать в чате и подтвердить.
     * [forward] — копия для отправки дальше (ttl - 1, мой id в hops), null — пересылать не надо.
     */
    data class Inbound(val deliver: Boolean, val forward: WireMessage?, val drop: Drop = Drop.NONE)

    /**
     * Решение по кадру [m], который мне прислал сосед [senderId]. Не смотрит на сеть и базу: нужные
     * сведения приходят аргументами.
     * [isMyGroup] — состою ли я в группе с таким id. [relayForeignGroups] — настройка «пересылать
     * сообщения групп, в которых я не состою» (по умолчанию выключена). [relayEnabled] — общая настройка
     * «пересылать чужие сообщения» (по умолчанию включена): выключена, и [Inbound.forward] всегда null.
     * Лишь адресованное мне личное сообщение дальше не идёт. Сообщение моей группы я и показываю,
     * и пересылаю: в группе могут быть участники за мной.
     */
    fun planInbound(
        m: WireMessage,
        myId: String,
        senderId: String,
        isMyGroup: (String) -> Boolean,
        relayForeignGroups: Boolean,
        relayEnabled: Boolean = true
    ): Inbound {
        fun dropped(why: Drop) = Inbound(false, null, why)
        if (m.isDirect) return dropped(Drop.DIRECT)
        // Последний в hops — тот, кто прислал: так чужой кадр не выдать за кадр соседа
        if (m.hops.isEmpty() || m.hops.first() != m.origin || m.hops.last() != senderId) return dropped(Drop.BAD_PATH)
        if (isLoop(m, myId)) return dropped(Drop.LOOP)
        val dest = m.dest ?: return dropped(Drop.NO_DEST)
        if (groupIdOfDest(dest) != m.groupId) return dropped(Drop.BAD_DEST)
        // Пересылка выключена настройкой: сообщения мне доставляем, остальное не трогаем
        val fwd = if (relayEnabled) forwardCopy(m, myId) else null
        val gid = m.groupId
        return when {
            dest == myId -> Inbound(true, null)
            gid != null -> {
                val member = isMyGroup(gid)
                Inbound(member, if (member || relayForeignGroups) fwd else null)
            }
            else -> Inbound(false, fwd)
        }
    }

    /**
     * Кому переслать: соседи без того, от кого кадр пришёл, и без узлов из hops (они его уже видели,
     * отправка им только тратила бы канал). [m] — копия уже с моим id в hops.
     */
    fun forwardTargets(m: WireMessage, neighbors: Collection<String>, cameFrom: String): List<String> =
        neighbors.filter { it != cameFrom && it !in m.hops }

    /** Сколько посредников было между автором и мной: в hops автор, посредники и тот, кто прислал мне. */
    fun viaCount(m: WireMessage): Int = (m.hops.size - 1).coerceAtLeast(0)

    /**
     * Ключ для [SeenCache]. У сообщения с точкой в него входит время замера, чтобы SOS с уточнённой
     * точкой (тот же id) не принимался за повтор и дошёл до получателя.
     */
    fun seenKey(m: WireMessage): String = m.geo?.let { "${m.id}@${it.fixTime}" } ?: m.id

    // ---------- ACK ----------
    // Выбран путь назад по hops, а не флуд. ACK крошечный и нужен только автору. По hops он идёт
    // одной копией ровно по пути, который кадр прошёл только что, и расходует столько передач,
    // сколько было переходов. Флуд размножал бы его по всем соседям и требовал бы своей памяти
    // повторов. Если звено пути пропало, ACK теряется, автор не получает подтверждения и шлёт
    // сообщение снова: получатель повторно подтверждает (запись в базе идемпотентна).

    /** ACK получателя [myId] на принятое пересланное сообщение [m]: путь назад — hops наоборот. */
    fun relayedAck(m: WireMessage, myId: String): Frame.Ack =
        Frame.Ack(m.id, to = m.origin ?: "", route = m.hops.reversed(), from = myId)

    /**
     * Что делать с пересланным ACK [a] на моём узле [myId].
     * [AckPlan.deliver] — он дошёл до автора (то есть до меня). Иначе [AckPlan.forward] отправляется
     * соседу [AckPlan.nextHop]. Если ничего нет — отбросить.
     */
    data class AckPlan(val deliver: Boolean = false, val nextHop: String? = null, val forward: Frame.Ack? = null)

    fun planAck(a: Frame.Ack, myId: String, relayEnabled: Boolean = true): AckPlan {
        val to = a.to ?: return AckPlan()
        if (a.route.firstOrNull() != myId) return AckPlan() // не мне: путь не сходится
        val rest = a.route.drop(1)
        if (rest.isEmpty()) return if (to == myId) AckPlan(deliver = true) else AckPlan()
        if (!relayEnabled) return AckPlan() // чужой ACK при выключенной пересылке не передаём
        return AckPlan(nextHop = rest[0], forward = a.copy(route = rest))
    }
}

/**
 * Память о уже виденных кадрах: ключ — автор + id сообщения. Нужна, чтобы кадр, пришедший
 * двумя путями (A-B-D и A-C-D), D обработал один раз. Хранит не больше [capacity] ключей,
 * самые старые вытесняются. Не потокобезопасна: вызывать с одного потока или под замком.
 */
class SeenCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private val keys = object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    val size: Int get() = keys.size

    /** true, если кадр новый (и теперь запомнен). false — повтор, кадр надо отбросить. */
    fun markNew(origin: String, msgId: String): Boolean = keys.put("$origin|$msgId", true) == null

    companion object {
        const val DEFAULT_CAPACITY = 512
    }
}
