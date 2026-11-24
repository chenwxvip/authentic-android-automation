package com.ayu.authentic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.ayu.authentic.config.Config
import com.ayu.authentic.util.ALog
import com.ayu.authentic.vision.CaptureState
import com.ayu.authentic.vision.FrameBus
import java.nio.ByteBuffer

/**
 * MVP 阶段二 · MediaProjection 实时截图服务。
 *
 * 授权链路：MainActivity 的「③ 授权屏幕截图」按钮 ->
 *   createScreenCaptureIntent() -> 系统弹窗 -> onActivityResult 拿到 resultData ->
 *   startForegroundService(ACTION_START + resultData) -> 本服务建立 VirtualDisplay +
 *   ImageReader，逐帧转成 Bitmap 并 publish 到 FrameBus。
 *
 * 真机 API 29：AccessibilityService.takeScreenshot() 需 API 30+，故必须走 MediaProjection。
 * 前台服务类型声明为 mediaProjection（Manifest 中已配置）。
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var orientationListener: android.view.OrientationEventListener? = null
    private var lastRotation = -1
    private var lastCapW = 0
    private var lastCapH = 0

    override fun onCreate() {
        super.onCreate()
        ALog.init(java.io.File(getExternalFilesDir(null), "authentic_log.txt").absolutePath)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先 startForeground（mediaProjection 类型服务要求），再建 VirtualDisplay
        startForeground(NOTIFICATION_ID, buildNotification("截图服务已启动，等待帧"))

        when (intent?.action) {
            ACTION_STOP -> {
                ALog.i("[截图] 收到停止指令")
                stopCapture()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    FrameBus.error("缺少 MediaProjection 授权 resultData，请先点「授权屏幕截图」")
                    ALog.w("[截图] 无授权数据，停止")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultData)
            }
            else -> ALog.w("[截图] 未知 action=${intent?.action}，保持待命")
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startCapture(resultData: Intent) {
        if (running) {
            ALog.i("[截图] 已在运行，忽略重复启动")
            return
        }
        FrameBus.reset()
        CaptureState.setRunning(this, true)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = runCatching { mpm.getMediaProjection(RESULT_OK, resultData) }.getOrElse {
            FrameBus.error("getMediaProjection 失败: ${it.message}")
            null
        }
        if (projection == null) return

        thread = HandlerThread("AuthenticCapture").also { it.start() }
        handler = Handler(thread!!.looper)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                ALog.w("[截图] MediaProjection 被系统停止")
                FrameBus.error("屏幕录制授权被系统回收")
                stopCapture()
            }
        }, handler)

        running = true
        // 首次建立
        rebuildVirtualDisplay()

        // 【阶段五修复】监听屏幕旋转：游戏由竖屏进横屏时，VirtualDisplay 必须重建。
        // 部分 ROM 的 OrientationEventListener 不触发，故再加一个定时兜底：
        // 每 2s 比对 getRealMetrics 的宽高，发现方向变了就重建（最可靠）。
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                checkAndRebuild("orientation")
            }
        }
        orientationListener?.enable()
        lastRotation = currentRotation()
        handler?.post(object : Runnable {
            override fun run() {
                if (!running) return
                checkAndRebuild("poll")
                handler?.postDelayed(this, 2000)
            }
        })
        ALog.i("[截图] 屏幕旋转监听 + 2s 轮询兜底已开启，VirtualDisplay 随方向动态适配")
    }

    /** 比对当前真实屏幕方向，与上次建屏时不一致就重建 */
    private fun checkAndRebuild(from: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val rot = currentRotation()
        val landscape = (rot == android.view.Surface.ROTATION_90 ||
            rot == android.view.Surface.ROTATION_270) || dm.widthPixels >= dm.heightPixels
        val lastLandscape = lastCapW >= lastCapH
        if (rot != lastRotation || landscape != lastLandscape) {
            ALog.i("[截图] 方向变化($from): ${lastCapW}x${lastCapH} -> rot=$rot ${dm.widthPixels}x${dm.heightPixels}，重建")
            lastRotation = rot
            rebuildVirtualDisplay()
        }
    }

    /** 当前屏幕旋转（0/1/2/3）；1/3 为横屏 */
    private fun currentRotation(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return wm.defaultDisplay.rotation
    }

    /**
     * 按当前真实屏幕尺寸重建 VirtualDisplay + ImageReader。
     * 关键：横屏时用 2400x1080，竖屏时用 1080x2400，
     * 让喂出的帧与 LoginSceneMatcher 的分组一致。
     */
    private fun rebuildVirtualDisplay() {
        val p = projection ?: return
        val h = handler ?: return
        if (!running) return

        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi
        // 【阶段五关键】华为等 ROM 在横屏游戏里 getRealMetrics 仍返回物理 1080x2400，
        // 只有 rotation 变为 1/3。因此必须用 rotation 判定方向，不能只看宽高。
        val rot = currentRotation()
        val landscape = (rot == android.view.Surface.ROTATION_90 ||
            rot == android.view.Surface.ROTATION_270) || width >= height
        val capW = if (landscape) Config.BASE_W else Config.BASE_H
        val capH = if (landscape) Config.BASE_H else Config.BASE_W
        lastCapW = capW
        lastCapH = capH
        ALog.i("[截图] 重建：真机 ${width}x${height}@$density rotation=${currentRotation()} -> 采帧 ${capW}x${capH} (${if (landscape) "横屏" else "竖屏"})")

        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val bmp = imageToBitmap(image)
            image.close()
            if (bmp != null) {
                FrameBus.publish(bmp)
                CaptureState.markFrame(this@CaptureService)
                val n = FrameBus.frameCount
                if (n == 1L || n % 30L == 0L) {
                    ALog.i("[截图] 已喂帧 $n 张 (${bmp.width}x${bmp.height})")
                }
                if (n == 1L) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification("已拿到游戏画面帧"))
                }
            }
        }, h)
        imageReader = reader

        virtualDisplay = p.createVirtualDisplay(
            "AuthenticCapture",
            capW, capH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, h
        )
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return runCatching {
            val plane = image.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val w = image.width + rowPadding / pixelStride
            val h = image.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            if (w == image.width) bmp else Bitmap.createBitmap(bmp, 0, 0, image.width, h)
        }.getOrElse {
            ALog.w("[截图] 帧转换失败: ${it.message}")
            null
        }
    }

    private fun stopCapture() {
        running = false
        CaptureState.setRunning(this, false)
        runCatching { orientationListener?.disable() }
        orientationListener = null
        lastRotation = -1
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { imageReader?.close() }; imageReader = null
        runCatching { projection?.stop() }; projection = null
        runCatching { thread?.quitSafely() }; thread = null
        handler = null
        FrameBus.reset()
        ALog.i("[截图] 已释放 VirtualDisplay / ImageReader")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕截图", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Authentic · 屏幕截图")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "authentic_capture"
        private const val NOTIFICATION_ID = 1002
        private const val RESULT_OK = -1

        const val ACTION_START = "com.ayu.authentic.CAPTURE_START"
        const val ACTION_STOP = "com.ayu.authentic.CAPTURE_STOP"
        const val EXTRA_RESULT_DATA = "result_data"

        /** MainActivity 授权成功后调用：把 resultData 交给本前台服务 */
        fun start(context: Context, resultData: Intent) {
            val i = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
