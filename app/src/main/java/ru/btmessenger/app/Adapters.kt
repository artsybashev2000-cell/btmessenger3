package ru.btmessenger.app

import android.bluetooth.BluetoothClass
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

private val AVATAR_COLORS = intArrayOf(
    0xFFE17076.toInt(), 0xFFFAA774.toInt(), 0xFFA695E7.toInt(), 0xFF7BC862.toInt(),
    0xFF6EC9CB.toInt(), 0xFF65AADD.toInt(), 0xFFEE7AAE.toInt()
)

fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun avatarColor(seed: String): Int =
    AVATAR_COLORS[(seed.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]

fun avatarLetter(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** Аватар группы: цветной круг с иконкой людей. [sizeDp] — размер круга, по нему считается отступ иконки. */
fun groupAvatar(ctx: Context, seed: String, sizeDp: Int): Drawable {
    val bg = circle(avatarColor(seed))
    val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_group) ?: return bg
    val pad = (ctx.resources.displayMetrics.density * sizeDp * 0.25f).toInt()
    return LayerDrawable(arrayOf(bg, icon)).apply { setLayerInset(1, pad, pad, pad, pad) }
}

/** Устройство, найденное поиском или сопряжённое. */
data class NearbyDevice(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val majorClass: Int?,
    val rssi: Int?,
    /** Слышно BLE-объявление BtMessenger: на телефоне точно есть приложение. */
    val ble: Boolean = false
) {
    val title: String get() = name.ifBlank { address }

    /** Наушники, часы, колонки и т. п. по умолчанию скрываем — к ним всё равно не подключиться. */
    fun looksLikePhone(): Boolean = when (majorClass) {
        null, BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.COMPUTER,
        BluetoothClass.Device.Major.MISC, BluetoothClass.Device.Major.UNCATEGORIZED -> true
        else -> false
    }
}

/** Строки главного списка. */
sealed class HomeRow {
    abstract val key: String

    data class Header(val title: String) : HomeRow() {
        override val key get() = "h:$title"
    }

    data class Hint(val text: String) : HomeRow() {
        override val key get() = "hint"
    }

    data class Peer(val summary: PeerSummary, val online: Boolean, val rssi: Int?) : HomeRow() {
        override val key get() = "p:" + summary.address
    }

    data class Device(val device: NearbyDevice) : HomeRow() {
        override val key get() = "d:" + device.address
    }

    /** Группа в списке чатов; [online] — сколько её участников сейчас на связи. */
    data class Group(val summary: GroupSummary, val online: Int) : HomeRow() {
        override val key get() = "g:" + summary.info.id
    }
}

/** Главный список: чаты (с кем уже общались) и устройства рядом. */
class HomeAdapter(
    private val onPeer: (PeerSummary) -> Unit,
    private val onPeerLong: (PeerSummary) -> Unit,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onGroup: (GroupSummary) -> Unit,
    private val onGroupLong: (GroupSummary) -> Unit
) : ListAdapter<HomeRow, RecyclerView.ViewHolder>(DIFF) {

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    class PeerVH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.avatar)
        val online: View = view.findViewById(R.id.onlineDot)
        val name: TextView = view.findViewById(R.id.name)
        val memberCount: TextView = view.findViewById(R.id.memberCount)
        val time: TextView = view.findViewById(R.id.time)
        val preview: TextView = view.findViewById(R.id.preview)
        val badge: TextView = view.findViewById(R.id.badge)
    }

    class DeviceVH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.avatar)
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
        val pin: ImageView = view.findViewById(R.id.pin)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HomeRow.Header -> T_HEADER
        is HomeRow.Hint -> T_HINT
        is HomeRow.Peer -> T_PEER
        is HomeRow.Group -> T_PEER // та же разметка строки, что у личного чата
        is HomeRow.Device -> T_DEVICE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEADER -> HeaderVH(inf.inflate(R.layout.item_header, parent, false))
            T_HINT -> HeaderVH(inf.inflate(R.layout.item_hint, parent, false))
            T_PEER -> PeerVH(inf.inflate(R.layout.item_peer, parent, false))
            else -> DeviceVH(inf.inflate(R.layout.item_device, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val row = getItem(position)) {
            is HomeRow.Header -> (holder as HeaderVH).text.text = row.title
            is HomeRow.Hint -> (holder as HeaderVH).text.text = row.text
            is HomeRow.Peer -> {
                val h = holder as PeerVH
                val s = row.summary
                h.avatar.text = avatarLetter(s.title)
                h.avatar.background = circle(avatarColor(s.address))
                h.online.visibility = if (row.online) View.VISIBLE else View.GONE
                h.name.text = s.title
                h.memberCount.visibility = View.GONE
                h.time.text = if (s.lastKind != null) Format.time(s.lastTime) else ""
                val sub = when {
                    s.lastKind != null -> Format.preview(ctx, s.lastKind, s.lastText, s.lastFromMe)
                    s.role.isNotBlank() -> s.role
                    else -> ctx.getString(R.string.peer_no_messages)
                }
                h.preview.text = when {
                    row.online -> ctx.getString(R.string.peer_online_prefix, sub)
                    row.rssi != null -> ctx.getString(R.string.peer_nearby_prefix, sub)
                    else -> sub
                }
                h.badge.visibility = if (s.unread > 0) View.VISIBLE else View.GONE
                h.badge.text = if (s.unread > 99) "99+" else s.unread.toString()
                h.itemView.setOnClickListener { onPeer(s) }
                h.itemView.setOnLongClickListener {
                    onPeerLong(s)
                    true
                }
            }
            is HomeRow.Group -> {
                val h = holder as PeerVH
                val s = row.summary
                val g = s.info
                h.avatar.text = ""
                h.avatar.background = groupAvatar(ctx, g.chatKey, 52)
                h.online.visibility = if (row.online > 0) View.VISIBLE else View.GONE
                h.name.text = g.name
                h.memberCount.visibility = View.VISIBLE
                h.memberCount.text = ctx.getString(R.string.group_member_badge, g.members.size)
                h.time.text = if (s.lastKind != null) Format.time(s.lastTime) else ""
                val sub = if (s.lastKind != null) {
                    val body = Format.preview(ctx, s.lastKind, s.lastText, s.lastFromMe)
                    if (!s.lastFromMe && s.lastAuthor.isNotBlank()) ctx.getString(R.string.preview_author, s.lastAuthor, body) else body
                } else {
                    ctx.getString(R.string.group_no_messages)
                }
                h.preview.text = if (row.online > 0) ctx.getString(R.string.peer_online_prefix, sub) else sub
                h.badge.visibility = if (g.unread > 0) View.VISIBLE else View.GONE
                h.badge.text = if (g.unread > 99) "99+" else g.unread.toString()
                h.itemView.setOnClickListener { onGroup(s) }
                h.itemView.setOnLongClickListener {
                    onGroupLong(s)
                    true
                }
            }
            is HomeRow.Device -> {
                val h = holder as DeviceVH
                val d = row.device
                val title = if (d.name.isBlank() && d.ble) ctx.getString(R.string.device_ble_title) else d.title
                h.avatar.text = avatarLetter(title)
                h.avatar.background = circle(avatarColor(d.address))
                h.name.text = title
                val parts = mutableListOf<String>()
                if (d.ble) parts += ctx.getString(R.string.device_ble_tag)
                if (d.bonded) parts += ctx.getString(R.string.device_bonded)
                parts += d.address
                d.rssi?.let { parts += Format.signal(ctx, it) }
                h.info.text = parts.joinToString(" · ")
                h.pin.visibility = if (d.bonded) View.VISIBLE else View.GONE
                h.itemView.setOnClickListener { onDevice(d) }
            }
        }
    }

    private companion object {
        const val T_HEADER = 0
        const val T_HINT = 1
        const val T_PEER = 2
        const val T_DEVICE = 3

        val DIFF = object : DiffUtil.ItemCallback<HomeRow>() {
            override fun areItemsTheSame(oldItem: HomeRow, newItem: HomeRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: HomeRow, newItem: HomeRow) = oldItem == newItem
        }
    }
}

/** Сообщения в виде пузырей: свои справа, чужие слева, SOS — красным, голосовые — с кнопкой «воспроизвести». */
class MessageAdapter(
    private val onGeoClick: (ChatMessage) -> Unit,
    private val onLongClick: (ChatMessage) -> Unit,
    private val onVoiceClick: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, MessageAdapter.VH>(DIFF) {

    /** Какое голосовое сообщение играет сейчас (null — никакое) и на какой миллисекунде. */
    private var playingId: String? = null
    private var playingPos = 0

    /** Обновить показ проигрывания: перерисовываются только затронутые голосовые, остальная лента не мигает. */
    fun setPlayback(id: String?, posMs: Int) {
        val old = playingId
        playingId = id
        playingPos = posMs
        if (old != null && old != id) changed(old)
        if (id != null) changed(id)
    }

    private fun changed(id: String) {
        val i = currentList.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i, PAYLOAD_PLAYBACK)
    }

    /** Имена авторов для группового чата: id установки → имя. Над входящим групповым сообщением показывается автор. */
    var authors: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val author: TextView = view.findViewById(R.id.msgAuthor)
        val sosTitle: TextView = view.findViewById(R.id.sosTitle)
        val text: TextView = view.findViewById(R.id.msgText)
        val geoMeta: TextView = view.findViewById(R.id.geoMeta)
        val geoLink: TextView = view.findViewById(R.id.geoLink)
        val voiceRow: View = view.findViewById(R.id.voiceRow)
        val voicePlay: ImageButton = view.findViewById(R.id.voicePlay)
        val voiceProgress: ProgressBar = view.findViewById(R.id.voiceProgress)
        val voiceLength: TextView = view.findViewById(R.id.voiceLength)
        val via: TextView = view.findViewById(R.id.msgVia)
        val time: TextView = view.findViewById(R.id.msgTime)
        val status: ImageView = view.findViewById(R.id.msgStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        // идёт только проигрывание: перерисовываем полосу, а не весь пузырь
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PLAYBACK }) {
            val m = getItem(position)
            if (m.kind == Kind.VOICE) bindVoice(holder, m)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindVoice(holder: VH, m: ChatMessage) {
        val ctx = holder.itemView.context
        val total = m.voiceMs
        val playing = playingId == m.id
        holder.voicePlay.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        holder.voicePlay.contentDescription = ctx.getString(if (playing) R.string.cd_voice_stop else R.string.cd_voice_play)
        val pos = if (playing) playingPos.coerceIn(0, total.coerceAtLeast(0)) else 0
        holder.voiceProgress.progress = if (playing && total > 0) (pos * 1000L / total).toInt().coerceIn(0, 1000) else 0
        holder.voiceLength.text = if (playing) {
            ctx.getString(R.string.voice_progress, Format.mmss(pos.toLong()), Format.mmss(total.toLong()))
        } else {
            Format.mmss(total.toLong())
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = getItem(position)
        val ctx = holder.itemView.context

        val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (m.fromMe) Gravity.END else Gravity.START
        holder.bubble.layoutParams = lp
        holder.bubble.setBackgroundResource(
            when {
                m.kind == Kind.SOS -> R.drawable.bg_bubble_sos
                m.fromMe -> R.drawable.bg_bubble_out
                else -> R.drawable.bg_bubble_in
            }
        )

        if (!m.fromMe && m.groupId != null) {
            val sender = m.senderId.orEmpty()
            holder.author.visibility = View.VISIBLE
            holder.author.text = authors[sender]?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.group_member_unknown)
            holder.author.setTextColor(avatarColor(sender))
        } else {
            holder.author.visibility = View.GONE
        }

        holder.sosTitle.visibility = if (m.kind == Kind.SOS) View.VISIBLE else View.GONE
        val geo = m.geo
        holder.text.text = when (m.kind) {
            Kind.GEO -> ctx.getString(R.string.geo_bubble, Format.coords(geo ?: GeoPoint(0.0, 0.0, null, 0)))
            Kind.SOS -> buildString {
                append(m.text.ifBlank { ctx.getString(R.string.sos_default_text) })
                if (geo != null) append("\n📍 ").append(Format.coords(geo))
                else append('\n').append(ctx.getString(R.string.sos_no_geo))
            }
            Kind.TEXT -> m.text
            Kind.VOICE -> "" // вместо текста — строка с кнопкой проигрывания (ниже)
        }
        holder.text.visibility = if (holder.text.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        if (m.kind == Kind.VOICE) {
            holder.voiceRow.visibility = View.VISIBLE
            holder.voicePlay.setOnClickListener { onVoiceClick(m) }
            bindVoice(holder, m)
        } else {
            holder.voiceRow.visibility = View.GONE
            holder.voicePlay.setOnClickListener(null)
        }

        if (geo != null) {
            holder.geoMeta.visibility = View.VISIBLE
            holder.geoMeta.text = Format.geoMeta(ctx, m)
            val stale = Format.fixAgeAtSend(m) > Format.STALE_FIX_MS
            holder.geoMeta.setTextColor(
                ContextCompat.getColor(ctx, if (stale) R.color.warning else R.color.tg_secondary_light)
            )
            holder.geoLink.visibility = View.VISIBLE
        } else {
            holder.geoMeta.visibility = View.GONE
            holder.geoLink.visibility = View.GONE
        }

        // Сообщение дошло через посредников: мелкая пометка «через N» рядом со временем
        if (!m.fromMe && m.via > 0) {
            holder.via.visibility = View.VISIBLE
            holder.via.text = ctx.getString(R.string.msg_via, m.via)
        } else {
            holder.via.visibility = View.GONE
        }

        holder.time.text = Format.time(m.sentAt)
        holder.time.setTextColor(
            ContextCompat.getColor(ctx, if (m.fromMe) R.color.tg_time_out else R.color.tg_secondary)
        )
        if (m.fromMe) {
            holder.status.visibility = View.VISIBLE
            holder.status.setImageResource(
                when (m.status) {
                    Status.PENDING -> R.drawable.ic_clock
                    Status.SENT -> R.drawable.ic_check
                    else -> R.drawable.ic_check_double
                }
            )
            holder.status.contentDescription = ctx.getString(
                when (m.status) {
                    Status.PENDING -> R.string.status_pending
                    Status.SENT -> R.string.status_sent
                    else -> R.string.status_delivered
                }
            )
        } else {
            holder.status.visibility = View.GONE
        }

        holder.bubble.setOnClickListener { if (m.geo != null) onGeoClick(m) }
        holder.bubble.isClickable = geo != null
        holder.bubble.setOnLongClickListener {
            onLongClick(m)
            true
        }
    }

    private companion object {
        const val PAYLOAD_PLAYBACK = "playback"

        val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
        }
    }
}
