package dev.nixup.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The messages of services.ntfy's topics (nix/ntfy.nix), which its listener (the engine's Ntfy, as root) hands to the app
 * through EventsReceiver: each one a notification, as ntfy's own app shows it. Its title is the message's, after the
 * emojis of its tags that are emoji short codes, else the topic's name; its priority is its channel (one for each of
 * ntfy's five, which Android's notification settings can change one by one); a tap opens its link (click), else the
 * topic's page on its server; and its buttons (actions: view, http, broadcast, copy; three at most, and one for its
 * attachment when there is room) do what ntfy's do. A message with the sequence_id of one before takes the place of its
 * notification, and that message's delete or clear takes the notification away.
 */
object NtfyMessages {
    const val ACTION = "dev.nixup.manager.action.NTFY"
    const val TAG = "ntfy"

    private class Channel(val id: String, val name: Int, val importance: Int)

    /** ntfy's priorities, 1 (min) to 5 (max, urgent), each with a channel of its own. */
    private val CHANNELS = listOf(
        Channel("ntfy_min", R.string.channel_ntfy_min, NotificationManager.IMPORTANCE_MIN),
        Channel("ntfy_low", R.string.channel_ntfy_low, NotificationManager.IMPORTANCE_LOW),
        Channel("ntfy_default", R.string.channel_ntfy_default, NotificationManager.IMPORTANCE_DEFAULT),
        Channel("ntfy_high", R.string.channel_ntfy_high, NotificationManager.IMPORTANCE_HIGH),
        Channel("ntfy_max", R.string.channel_ntfy_max, NotificationManager.IMPORTANCE_HIGH),
    )

    /** [json]: the message as the server sent it; [url]: its topic's address; [name]: the topic's name on the phone. */
    fun show(context: Context, json: String, url: String, name: String) {
        val message = runCatching { JSONObject(json) }.getOrNull() ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = "$url/${message.optString("sequence_id").ifEmpty { message.optString("id") }}".hashCode()
        when (message.optString("event")) {
            "message" -> {}
            "message_delete", "message_clear" -> return manager.cancel(TAG, id)
            else -> return
        }
        // (Made with the first message: a phone without services.ntfy has none of them in its settings.)
        manager.createNotificationChannels(
            CHANNELS.map { channel ->
                NotificationChannel(channel.id, context.getString(channel.name), channel.importance).apply {
                    // Urgent: long vibration bursts, as ntfy's app has them.
                    if (channel.id == "ntfy_max") {
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                    }
                }
            },
        )
        val priority = message.optInt("priority", 3).takeIf { it in 1..5 } ?: 3
        val tags = message.optJSONArray("tags")?.let { tags -> (0 until tags.length()).map { tags.optString(it) } }.orEmpty()
        val emojis = tags.mapNotNull { EMOJIS[it] }.joinToString("")
        val title = message.optString("title")
        val attachment = message.optJSONObject("attachment")
        val text = message.optString("message").ifEmpty { attachment?.let { context.getString(R.string.ntfy_file, it.optString("name")) }.orEmpty() }
        val shownTitle = if (title.isNotEmpty()) withEmojis(emojis, title) else name.ifEmpty { url.substringAfter("://") }
        val shownText = if (title.isEmpty()) withEmojis(emojis, text) else text
        val builder = Notification.Builder(context, CHANNELS[priority - 1].id)
            .setSmallIcon(R.drawable.ic_stat_ntfy)
            .setContentTitle(shownTitle)
            .setContentText(shownText)
            .setStyle(Notification.BigTextStyle().bigText(shownText))
            .setWhen(message.optLong("time", System.currentTimeMillis() / 1000) * 1000)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(view(context, message.optString("click").ifEmpty { url }))
        // Which topic it came from, when its title is the message's own.
        if (title.isNotEmpty()) builder.setSubText(name)
        var buttons = 0
        val actions = message.optJSONArray("actions")
        for (i in 0 until (actions?.length() ?: 0)) {
            if (buttons == 3) break
            val action = actions?.optJSONObject(i) ?: continue
            val label = action.optString("label").ifEmpty { null } ?: continue
            val intent = when (action.optString("action")) {
                "view" -> action.optString("url").ifEmpty { null }?.let { view(context, it) }
                "http", "broadcast", "copy" -> act(context, action, id, buttons)
                else -> null
            } ?: continue
            builder.addAction(Notification.Action.Builder(null as Icon?, label, intent).build())
            buttons++
        }
        val file = attachment?.optString("url")?.ifEmpty { null }
        if (file != null && buttons < 3) builder.addAction(Notification.Action.Builder(null as Icon?, context.getString(R.string.ntfy_open_file), view(context, file)).build())
        manager.notify(TAG, id, builder.build())
    }

    private fun withEmojis(emojis: String, text: String) = if (emojis.isEmpty()) text else "$emojis $text"

    /** Opens [url] (a web page, or an app's link) in the app that takes it. */
    private fun view(context: Context, url: String): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A button the app does itself (see NtfyActionReceiver). */
    private fun act(context: Context, action: JSONObject, id: Int, index: Int): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, NtfyActionReceiver::class.java)
            // (An address of its own, so each button of each notification has its own PendingIntent.)
            .setData(Uri.parse("nixup-ntfy://action/$id/$index"))
            .putExtra(NtfyActionReceiver.EXTRA_ACTION, action.toString())
            .putExtra(NtfyActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The tags that are emoji short codes, as ntfy takes them (GitHub's names): the ones most used, shown before the
     * title. Another tag shows nothing, as in ntfy's own notifications.
     */
    private val EMOJIS: Map<String, String> by lazy {
        """
        warning ⚠️ rotating_light 🚨 skull 💀 skull_and_crossbones ☠️ x ❌ heavy_multiplication_x ✖️ white_check_mark ✅
        heavy_check_mark ✔️ ballot_box_with_check ☑️ no_entry ⛔ no_entry_sign 🚫 stop_sign 🛑 construction 🚧
        exclamation ❗ heavy_exclamation_mark ❗ grey_exclamation ❕ question ❓ grey_question ❔ bangbang ‼️
        information_source ℹ️ sos 🆘 new 🆕 ok 🆗 up 🆙 cool 🆒 free 🆓 bell 🔔 no_bell 🔕 loudspeaker 📢 mega 📣
        tada 🎉 partying_face 🥳 confetti_ball 🎊 sparkles ✨ star ⭐ star2 🌟 fire 🔥 boom 💥 zap ⚡ 100 💯
        +1 👍 thumbsup 👍 -1 👎 thumbsdown 👎 ok_hand 👌 clap 👏 wave 👋 pray 🙏 muscle 💪 eyes 👀 heart ❤️
        broken_heart 💔 smile 😄 smiley 😃 grinning 😀 joy 😂 wink 😉 sunglasses 😎 thinking 🤔 neutral_face 😐
        confused 😕 worried 😟 cry 😢 sob 😭 rage 😡 angry 😠 scream 😱 sleeping 😴 nerd_face 🤓 robot 🤖 ghost 👻
        alien 👽 poop 💩 hankey 💩 see_no_evil 🙈 computer 💻 desktop_computer 🖥️ keyboard ⌨️ iphone 📱 calling 📲
        phone ☎️ telephone_receiver 📞 email 📧 envelope ✉️ incoming_envelope 📨 mailbox 📫 package 📦 floppy_disk 💾
        cd 💿 dvd 📀 file_folder 📁 open_file_folder 📂 page_facing_up 📄 memo 📝 pencil2 ✏️ clipboard 📋 calendar 📆
        date 📅 alarm_clock ⏰ stopwatch ⏱️ hourglass ⌛ hourglass_flowing_sand ⏳ watch ⌚ lock 🔒 unlock 🔓 key 🔑
        closed_lock_with_key 🔐 shield 🛡️ gear ⚙️ wrench 🔧 hammer 🔨 hammer_and_wrench 🛠️ nut_and_bolt 🔩 link 🔗
        paperclip 📎 pushpin 📌 mag 🔍 bulb 💡 battery 🔋 electric_plug 🔌 satellite 📡 globe_with_meridians 🌐
        earth_africa 🌍 earth_americas 🌎 earth_asia 🌏 cloud ☁️ sunny ☀️ umbrella ☔ snowflake ❄️ thermometer 🌡️
        droplet 💧 ocean 🌊 house 🏠 door 🚪 car 🚗 rocket 🚀 airplane ✈️ ambulance 🚑 police_car 🚓 fire_engine 🚒
        hospital 🏥 pill 💊 syringe 💉 bug 🐛 duck 🦆 cat 🐱 dog 🐶 moneybag 💰 money_with_wings 💸 dollar 💵
        credit_card 💳 chart_with_upwards_trend 📈 chart_with_downwards_trend 📉 bar_chart 📊 gift 🎁 trophy 🏆 crown 👑
        coffee ☕ beer 🍺 pizza 🍕 birthday 🎂 cake 🍰 musical_note 🎵 headphones 🎧 microphone 🎤 camera 📷
        movie_camera 🎥 tv 📺 radio 📻 speaker 🔈 mute 🔇 sound 🔉 loud_sound 🔊 red_circle 🔴 orange_circle 🟠
        yellow_circle 🟡 green_circle 🟢 large_blue_circle 🔵 purple_circle 🟣 black_circle ⚫ white_circle ⚪
        red_square 🟥 yellow_square 🟨 green_square 🟩 arrow_up ⬆️ arrow_down ⬇️ arrow_right ➡️ arrow_left ⬅️
        arrows_counterclockwise 🔄 recycle ♻️ checkered_flag 🏁 triangular_flag_on_post 🚩
        """.trim().split(Regex("\\s+")).chunked(2).associate { it[0] to it[1] }
    }
}

/** What a button of an ntfy message does when it is not a link to open (see NtfyMessages), as ntfy's app does it. */
class NtfyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = runCatching { JSONObject(intent.getStringExtra(EXTRA_ACTION).orEmpty()) }.getOrNull() ?: return
        val app = context.applicationContext
        val label = action.optString("label")
        // clear: the notification goes once its button did what it says.
        fun done() {
            if (action.optBoolean("clear")) app.getSystemService(NotificationManager::class.java).cancel(NtfyMessages.TAG, intent.getIntExtra(EXTRA_ID, 0))
        }
        when (action.optString("action")) {
            "copy" -> {
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, action.optString("value")))
                done()
            }
            "broadcast" -> {
                val out = Intent(action.optString("intent").ifEmpty { "io.heckel.ntfy.USER_ACTION" })
                action.optJSONObject("extras")?.let { extras -> extras.keys().forEach { out.putExtra(it, extras.optString(it)) } }
                app.sendBroadcast(out)
                done()
            }
            "http" -> {
                val pending = goAsync()
                thread {
                    try {
                        val problem = request(action)
                        if (problem == null) {
                            done()
                        } else {
                            Handler(Looper.getMainLooper()).post { Toast.makeText(app, app.getString(R.string.ntfy_action_failed, label, problem), Toast.LENGTH_LONG).show() }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    /** ntfy's http button: its request (a POST, unless it says another method), with its headers and body; null once it went through, else why not. */
    private fun request(action: JSONObject): String? = try {
        val method = action.optString("method").ifEmpty { "POST" }.uppercase()
        val connection = (URL(action.getString("url")).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Nixup")
            action.optJSONObject("headers")?.let { headers -> headers.keys().forEach { setRequestProperty(it, headers.optString(it)) } }
        }
        try {
            val body = action.optString("body")
            if (method in listOf("POST", "PUT", "PATCH") || (body.isNotEmpty() && method !in listOf("GET", "HEAD"))) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            if (status in 200..299) null else "HTTP $status"
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_ID = "id"
    }
}
