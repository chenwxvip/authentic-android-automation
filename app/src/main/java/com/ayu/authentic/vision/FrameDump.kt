package com.ayu.authentic.vision

import android.content.Context
import android.graphics.Bitmap
import com.ayu.authentic.util.ALog
import java.io.File
import java.io.FileOutputStream

/**
 * 调试用：把 MediaProjection 的实时帧存成 PNG，便于真机实测后
 * adb pull 下来精确定位按钮（尤其是 UE4 游戏界面，系统截屏常抓黑）。
 *
 * 存到 app 私有外部目录 /sdcard/Android/data/<pkg>/files/frames/，无需存储权限。
 */
object FrameDump {

    fun save(context: Context, frame: Bitmap, tag: String) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "frames")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "$tag.png")
            FileOutputStream(f).use { out ->
                frame.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            ALog.i("[存帧] 已保存 ${f.absolutePath} (${frame.width}x${frame.height})")
        }.onFailure {
            ALog.e("[存帧] 失败: ${it.message}")
        }
    }
}
