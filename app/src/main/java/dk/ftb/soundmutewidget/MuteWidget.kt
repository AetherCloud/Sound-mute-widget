package dk.ftb.soundmutewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.VectorDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
import kotlin.math.max

class MuteWidget : AppWidgetProvider() {

	private val updateHandler = Handler(Looper.getMainLooper())
	private var liveContext: Context? = null
	private var updateScheduled = false

	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
		updateAllWidgets(context)
	}

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			ACTION_MUTE -> {
				// The tap may be what revived a killed process — re-arm the heartbeat
				// before anything else, so it survives the next kill too.
				scheduleHeartbeat(context)
				val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
				val previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
				audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)

				// Only confirm visually if the volume really is 0 now — setStreamVolume is
				// synchronous, so re-reading verifies the mute actually took effect.
				if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
					val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
					if (previousVolume > 0 && maxVolume > 0) {
						playSweep(context, previousVolume.toFloat() / maxVolume)
					} else {
						showMutedFace(context)
					}
				}
			}
			// The widget's process — and with it the volume observer — does not survive a
			// reboot, app update, or OEM cache kill. These paths revive us, so re-render
			// and re-register.
			ACTION_HEARTBEAT -> {
				val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
				if (power.isInteractive) {
					updateAllWidgets(context)
				} else if (hasPlacedWidgets(context)) {
					// Screen off — nothing visible to keep fresh. Keep the chain armed
					// (device idle defers it anyway) so it resumes when the screen does.
					scheduleHeartbeat(context)
				}
			}
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_MY_PACKAGE_REPLACED -> updateAllWidgets(context)
		}
		super.onReceive(context, intent)
	}

	/**
	 * Feedback when the widget is pressed while already muted: the same
	 * transition, but from the muted state — the ring layer flips between two
	 * identical empty rings, which is invisible (an empty ring is rotationally
	 * symmetric), so all that reads is the glyph popping in again.
	 */
	private fun showMutedFace(context: Context) {
		playSweep(context, 0f)
	}

	/**
	 * One launcher-side transition to the muted face. The filled ring rotates
	 * back toward 12 o'clock and fades out while the empty ring fades in beneath
	 * it, and the percentage shrinks out as the muted glyph pops in. The
	 * animations run on the launcher's frame loop — the display's refresh rate —
	 * so this is the one flavor of widget animation with no cross-process
	 * stepping involved.
	 *
	 * The outgoing frame loads onto whichever child is currently displayed (a
	 * bitmap swap there is instant, so the sweep always starts from what's on
	 * screen) and the flip goes to the other child — flipping to the child that's
	 * already showing would replay only the in-animation, with no outgoing frame,
	 * so the target alternates and [updateAllWidgets] re-syncs to child A.
	 */
	private fun playSweep(context: Context, fromFraction: Float) {
		val manager = AppWidgetManager.getInstance(context)
		val ids = manager.getAppWidgetIds(ComponentName(context, MuteWidget::class.java))
		val views = RemoteViews(context.packageName, R.layout.widget_mute)
		val from = displayedChild
		val to = 1 - from
		views.setImageViewBitmap(ringChild(from), renderRing(context, FACE_SIZE_DP, fromFraction))
		views.setImageViewBitmap(ringChild(to), renderRing(context, FACE_SIZE_DP, 0f))
		views.setImageViewBitmap(centerChild(from), renderCenter(context, FACE_SIZE_DP, fromFraction))
		views.setImageViewBitmap(centerChild(to), renderCenter(context, FACE_SIZE_DP, 0f))
		views.setInt(R.id.ring_flipper, "setDisplayedChild", to)
		views.setInt(R.id.center_flipper, "setDisplayedChild", to)
		views.setOnClickPendingIntent(R.id.widget_root, mutePendingIntent(context))
		manager.updateAppWidget(ids, views)
		displayedChild = to

		// Volume updates hold off until the transition has finished animating.
		pulseUntil = SystemClock.elapsedRealtime() + SWEEP_SETTLE_MS
	}

	private fun ringChild(child: Int) = if (child == 0) R.id.ring_a else R.id.ring_b

	private fun centerChild(child: Int) = if (child == 0) R.id.center_a else R.id.center_b

	/**
	 * Redraws every placed instance of the widget with the current media volume, and
	 * makes sure the volume observer is registered and the heartbeat armed — every
	 * render is also a chance to come back to life after the process was killed.
	 *
	 * The fresh face goes into BOTH children of each flipper: after a restart the
	 * process can't know which child the launcher is showing (it keeps whatever
	 * state we last applied), so whichever is on screen must be refreshed. The
	 * flipper itself is only touched when it needs re-syncing to child A —
	 * setDisplayedChild replays the in-animation even on the child that is
	 * already displayed, which would read as a flash on every volume change
	 * and every heartbeat.
	 */
	private fun updateAllWidgets(context: Context) {
		val manager = AppWidgetManager.getInstance(context)
		val ids = manager.getAppWidgetIds(ComponentName(context, MuteWidget::class.java))
		if (ids.isEmpty()) return
		ensureLiveUpdates(context.applicationContext)
		val views = RemoteViews(context.packageName, R.layout.widget_mute)
		val ring = renderRing(context, FACE_SIZE_DP)
		val center = renderCenter(context, FACE_SIZE_DP)
		views.setImageViewBitmap(R.id.ring_a, ring)
		views.setImageViewBitmap(R.id.ring_b, ring)
		views.setImageViewBitmap(R.id.center_a, center)
		views.setImageViewBitmap(R.id.center_b, center)
		if (displayedChild != 0) {
			views.setInt(R.id.ring_flipper, "setDisplayedChild", 0)
			views.setInt(R.id.center_flipper, "setDisplayedChild", 0)
		}
		views.setOnClickPendingIntent(R.id.widget_root, mutePendingIntent(context))
		manager.updateAppWidget(ids, views)
		displayedChild = 0
		scheduleHeartbeat(context)
	}

	/**
	 * The ring layer: a faint full track with a bright arc up to the current volume,
	 * starting at 12 o'clock, sized relative to the face so it scales up with it.
	 * floating directly on the wallpaper — there is no backing disc.
	 */
	private fun renderRing(
		context: Context,
		faceSizeDp: Float,
		volumeFraction: Float? = null,
	): Bitmap {
		val density = context.resources.configuration.densityDpi / 160f
		val size = (faceSizeDp * density).toInt()
		val faceRadius = size / 2f - 2 * density // inset keeps the face off the bitmap edge
		val c = size / 2f

		// volumeFraction overrides the live read — for the sweep's outgoing frame,
		// which must show the pre-mute level even though the volume is already 0.
		val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
		val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
		val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
		val fraction = volumeFraction ?: (if (maxVolume > 0) volume.toFloat() / maxVolume else 0f)

		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)

		// Theme-aware palette, picked at render time so the face matches the OS.
		val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
			Configuration.UI_MODE_NIGHT_YES
		val content = if (dark) COLOR_CONTENT_DARK else COLOR_CONTENT_LIGHT
		val trackColor = if (dark) COLOR_TRACK_DARK else COLOR_TRACK_LIGHT

		val ringStroke = max(3f, faceSizeDp * 0.055f) * density
		val ringRadius = faceRadius - ringStroke / 2f - density
		val ringRect = RectF(c - ringRadius, c - ringRadius, c + ringRadius, c + ringRadius)
		val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = ringStroke
			color = trackColor
		}
		canvas.drawCircle(c, c, ringRadius, track)
		if (fraction > 0f) {
			val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				style = Paint.Style.STROKE
				strokeWidth = ringStroke
				strokeCap = Paint.Cap.ROUND
				color = content
			}
			canvas.drawArc(ringRect, -90f, 360f * fraction, false, progress)
		}
		return bitmap
	}

	/**
	 * The center layer: the volume as a percentage while there is any, or the muted
	 * glyph when the ring is empty.
	 */
	private fun renderCenter(context: Context, faceSizeDp: Float, volumeFraction: Float? = null): Bitmap {
		val density = context.resources.configuration.densityDpi / 160f
		val size = (faceSizeDp * density).toInt()
		val faceRadius = size / 2f - 2 * density
		val c = size / 2f

		val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
		val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
		val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
		val fraction = volumeFraction ?: (if (maxVolume > 0) volume.toFloat() / maxVolume else 0f)

		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)

		val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
			Configuration.UI_MODE_NIGHT_YES
		val content = if (dark) COLOR_CONTENT_DARK else COLOR_CONTENT_LIGHT
		val mutedColor = if (dark) COLOR_MUTED_DARK else COLOR_MUTED_LIGHT

		if (fraction > 0f) {
			// Volume percentage, centered on the face.
			val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
				color = content
				textAlign = Paint.Align.CENTER
				typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
				textSize = faceSizeDp * 0.22f * density
			}
			val baseline = c - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
			canvas.drawText("${(fraction * 100 + 0.5f).toInt()}%", c, baseline, text)
		} else {
			// Muted: the glyph, tinted to match the muted-red accent.
			val icon = context.getDrawable(R.drawable.ic_mute) as? VectorDrawable
			if (icon != null) {
				icon.mutate().setTint(mutedColor)
				val half = (faceRadius - 2 * density) * 0.6f
				icon.setBounds((c - half).toInt(), (c - half).toInt(), (c + half).toInt(), (c + half).toInt())
				icon.draw(canvas)
			}
		}
		return bitmap
	}

	/**
	 * Android has no public volume-changed broadcast, so external changes (the
	 * hardware volume keys) are caught two ways, both only while the widget's
	 * process is alive: a hidden-but-stable AudioService broadcast fired
	 * immediately on every change, and — as a fallback for OEMs that don't send
	 * it — an observer on the settings tables the volumes live in. OEMs kill the
	 * cached process on their own schedule — One UI within a minute or two —
	 * which takes these signals with it, so [updateAllWidgets] also arms the
	 * heartbeat alarm to revive us about once a minute while the screen is on.
	 * That is the tradeoff for not running a service.
	 */
	private inner class VolumeObserver(context: Context, handler: Handler) : ContentObserver(handler) {
		private val appContext = context.applicationContext
		private var lastVolume = currentVolume()

		override fun onChange(selfChange: Boolean) {
			val volume = currentVolume()
			if (volume != lastVolume) {
				lastVolume = volume
				scheduleUpdate()
			}
		}

		private fun currentVolume(): Int =
			(appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
				.getStreamVolume(AudioManager.STREAM_MUSIC)
	}

	/** Re-renders on volume broadcasts and on configuration changes (theme flips). */
	private val volumeReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			scheduleUpdate()
		}
	}

	/**
	 * Coalesces bursts of change signals — dragging the volume HUD fires one
	 * broadcast per step — into a single widget re-render shortly after the last.
	 */
	private fun scheduleUpdate() {
		val context = liveContext ?: return
		if (updateScheduled) return
		updateScheduled = true
		val pendingUpdate = object : Runnable {
			override fun run() {
				// A pulse in flight owns the display — running now would cut the
				// highlight short, so wait for the sweep to settle instead of
				// dropping the change on the floor.
				val wait = pulseUntil - SystemClock.elapsedRealtime()
				if (wait > 0) {
					updateHandler.postDelayed(this, wait)
				} else {
					updateScheduled = false
					updateAllWidgets(context)
				}
			}
		}
		updateHandler.postDelayed(pendingUpdate, UPDATE_DEBOUNCE_MS)
	}

	private fun hasPlacedWidgets(context: Context): Boolean =
		AppWidgetManager.getInstance(context)
			.getAppWidgetIds(ComponentName(context, MuteWidget::class.java))
			.isNotEmpty()

	/**
	 * Arms the one-shot alarm that revives the process if One UI (or anything
	 * else) kills it: on firing, [ACTION_HEARTBEAT] re-renders the face,
	 * re-registers the volume observers, and re-arms the chain — a
	 * self-perpetuating cycle with no service and no notification. The alarm is
	 * inexact and non-waking, so while the device is idle it simply stops
	 * firing and picks back up when the screen does. Every render re-arms it,
	 * resetting the timer, so a kill is noticed at most one period after the
	 * last render.
	 */
	private fun scheduleHeartbeat(context: Context) {
		val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		val intent = Intent(context, MuteWidget::class.java).setAction(ACTION_HEARTBEAT)
		val pending = PendingIntent.getBroadcast(
			context,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
		alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + HEARTBEAT_PERIOD_MS, pending)
	}

	private fun mutePendingIntent(context: Context): PendingIntent {
		val intent = Intent(context, MuteWidget::class.java).setAction(ACTION_MUTE)
		return PendingIntent.getBroadcast(
			context,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	/**
	 * Registers the volume change listeners once per process. Synchronized because
	 * widget broadcasts can arrive in quick succession (boot → update → user tap).
	 */
	private fun ensureLiveUpdates(context: Context) {
		if (observerRegistered) return
		synchronized(MuteWidget::class.java) {
			if (observerRegistered) return
			liveContext = context
			// Primary signal: sent by AudioService immediately on every volume
			// change. Hidden API, but stable for years and used widely; if it ever
			// stops firing, the settings observer below still catches the change.
			context.registerReceiver(
				volumeReceiver,
				IntentFilter(ACTION_VOLUME_CHANGED),
				Context.RECEIVER_NOT_EXPORTED,
			)
			context.registerReceiver(
				volumeReceiver,
				IntentFilter(ACTION_STREAM_MUTE_CHANGED),
				Context.RECEIVER_NOT_EXPORTED,
			)
			// The launcher does not redraw widgets when the system theme changes —
			// this is what flips the palette when the user toggles dark mode.
			context.registerReceiver(
				volumeReceiver,
				IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
				Context.RECEIVER_NOT_EXPORTED,
			)
			// Fallback: the settings-table write lags the broadcast slightly, but it
			// catches OEMs that don't send it. Observing both roots with descendants
			// covers whichever table the OEM stores volumes in.
			val observer = VolumeObserver(context, updateHandler)
			context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
			context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
			observerRegistered = true
		}
	}

	companion object {
		private const val ACTION_MUTE = "dk.ftb.soundmutewidget.ACTION_MUTE"
		private const val ACTION_HEARTBEAT = "dk.ftb.soundmutewidget.ACTION_HEARTBEAT"
		private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
		private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
		private const val UPDATE_DEBOUNCE_MS = 150L

		/**
		 * How often the keep-alive alarm fires while the screen is on. It exists to
		 * notice the process was killed (One UI does so within a minute or two) and
		 * revive it, not to poll the volume — while the process is alive, the
		 * observers above update it instantly and a firing is only a cheap
		 * re-render. The cost that scales with this is process restarts: each
		 * period after a kill means one cold start of a dead process.
		 */
		private const val HEARTBEAT_PERIOD_MS = 30_000L

		/**
		 * How long volume updates hold off after a sweep, covering the longest
		 * transition (the ring's 350ms) plus launcher scheduling slop.
		 */
		private const val SWEEP_SETTLE_MS = 550L

		/**
		 * Which flipper child is on screen. Sweeps alternate the target (flipping
		 * to the already-displayed child wouldn't animate), and updateAllWidgets
		 * re-syncs this to 0 with an explicit setDisplayedChild.
		 */
		@Volatile
		private var displayedChild = 0

		/** While a mute pulse is in flight (elapsedRealtime-based). */
		@Volatile
		private var pulseUntil = 0L

		/**
		 * The face's edge length in dp. Manual — tune this to taste; it replaces the
		 * earlier auto-detected launcher cell size (which, scaled by the former 0.6
		 * factor on a typical Samsung cell, came out around this value).
		 */
		private const val FACE_SIZE_DP = 42f

		// Dark theme: white content straight on the wallpaper.
		private const val COLOR_CONTENT_DARK = 0xFFFFFFFF.toInt()
		private const val COLOR_TRACK_DARK = 0x33FFFFFF
		private const val COLOR_MUTED_DARK = 0xFFFF6B6B.toInt()

		// Light theme: near-black content, deeper red for contrast on bright walls.
		private const val COLOR_CONTENT_LIGHT = 0xFF1B1B1F.toInt()
		private const val COLOR_TRACK_LIGHT = 0x241B1B1F
		private const val COLOR_MUTED_LIGHT = 0xFFD93036.toInt()

		@Volatile
		private var observerRegistered = false
	}
}
