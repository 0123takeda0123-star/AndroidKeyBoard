package jp.takeda0123.fiftyonkeyboardime

import android.content.Context
import java.io.File

object ImeLogger {
    private const val FILE_NAME = "ime_log.txt"

    @Synchronized
    fun log(context: Context, msg: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val file = File(dir, FILE_NAME)
            val time = System.currentTimeMillis()
            file.appendText("$time | $msg\n")
        } catch (_: Exception) {
            // 絶対にIMEを落とさない
        }
    }
}
