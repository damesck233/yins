package moe.damesck.yins.testapp

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileInputStream

/**
 * Probe app for the yins module. Requests the permissions a nosy app would, then reads the photo
 * library through every path an app has (MediaStore, raw file paths) and reports what it saw.
 */
class MainActivity : ComponentActivity() {
    private var log by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var path by remember { mutableStateOf("/sdcard/DCIM/Camera") }
            Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("yins 探针", style = MaterialTheme.typography.titleLarge)
                Text(
                    "先申请权限，再点「一键检测」。装了 yins 并设为空白/部分时，检测应报告看不到别人的照片。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = {
                        requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO), 1)
                    }) { Text("申请相册权限") }
                    Button(modifier = Modifier.weight(1f), onClick = {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    }) { Text("申请全部文件") }
                }
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    // Mixed request: camera (non-storage) + photos (storage) together, to test that
                    // yins handles the storage part and the system handles the camera part.
                    requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES), 2)
                }) { Text("申请相机+相册（混合）") }
                Button(modifier = Modifier.fillMaxWidth(), onClick = { probe("一键检测") { fullReport() } }) {
                    Text("一键检测")
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { probe("列 /sdcard 根目录") { list("/storage/emulated/0") } }) {
                    Text("列 /sdcard 根目录")
                }

                Spacer(Modifier.height(8.dp))
                Text("单项探测", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = path, onValueChange = { path = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("路径") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { probe("list $path") { list(path) } }) { Text("列目录") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { probe("read $path") { readHead(path) } }) { Text("读文件") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { probe("保存自己的图片") { saveImage() } }) { Text("保存一张图") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { probe("写自定义目录") { writeCustomDir() } }) { Text("写 /sdcard/YinsTest") }
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { log = "" }) { Text("清空输出") }

                Spacer(Modifier.height(8.dp))
                Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    /** Runs [action] off the main thread (FUSE directory listings can take seconds) and reports timing. */
    private fun probe(title: String, action: () -> String) {
        append(title, "…运行中")
        Thread {
            val t0 = System.nanoTime()
            val result = runCatching(action).getOrElse { "EXC: $it" }
            val ms = (System.nanoTime() - t0) / 1_000_000
            runOnUiThread { replaceFirst(title, "$result\n[总耗时 ${ms}ms]") }
        }.start()
    }

    private fun append(title: String, result: String) {
        log = "== $title ==\n$result\n\n$log"
    }

    private fun replaceFirst(title: String, result: String) {
        val marker = "== $title ==\n…运行中\n\n"
        log = if (log.contains(marker)) log.replaceFirst(marker, "== $title ==\n$result\n\n") else "== $title ==\n$result\n\n$log"
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        append(
            "onRequestPermissionsResult",
            permissions.zip(grantResults.toList()).joinToString("\n") { "${it.first.removePrefix("android.permission.")} -> ${if (it.second == 0) "GRANTED" else "denied"}" },
        )
    }

    // ---------------------------------------------------------------- full report

    private fun fullReport(): String = buildString {
        appendLine("[权限状态]")
        for (p in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            appendLine("  ${p.removePrefix("android.permission.")}: ${if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "denied"}")
        }
        appendLine("  全部文件访问: ${Environment.isExternalStorageManager()}")

        appendLine("[MediaStore 图片]")
        val (img, imgMs) = timed { countMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) }
        val (imgTotal, imgOwn, imgSample) = img
        appendLine("  可见 $imgTotal 张，其中自己写入的 $imgOwn 张  [${imgMs}ms]")
        imgSample.forEach { appendLine("    $it") }

        appendLine("[MediaStore 视频]")
        val (vid, vidMs) = timed { countMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) }
        val (vidTotal, vidOwn, _) = vid
        appendLine("  可见 $vidTotal 个，其中自己写入的 $vidOwn 个  [${vidMs}ms]")

        appendLine("[MediaStore 全部文件]")
        val (files, fMs) = timed { countMedia(MediaStore.Files.getContentUri("external")) }
        val (fTotal, fOwn, _) = files
        appendLine("  可见 $fTotal 个，其中自己写入的 $fOwn 个  [${fMs}ms]")

        appendLine("[文件路径列目录]")
        for (dir in listOf("/sdcard", "/sdcard/DCIM", "/sdcard/DCIM/Camera", "/sdcard/Pictures", "/sdcard/Pictures/Screenshots", "/sdcard/Download")) {
            val (names, listMs) = timed { File(dir).list() }
            val files = names?.count { File(dir, it).isFile } ?: -1
            val dirs = names?.count { File(dir, it).isDirectory } ?: -1
            appendLine("  $dir: ${if (names == null) "无法列出" else "$files 个文件, $dirs 个子目录"}  [list ${listMs}ms]")
            names?.filter { File(dir, it).isFile }?.take(3)?.forEach { appendLine("      $it") }
        }

        appendLine("[结论]")
        val foreign = (imgTotal - imgOwn) + (vidTotal - vidOwn)
        val cameraFiles = File("/sdcard/DCIM/Camera").list()?.count { File("/sdcard/DCIM/Camera", it).isFile && !it.startsWith("yins_") } ?: 0
        val screenshotFiles = File("/sdcard/Pictures/Screenshots").list()?.count { File("/sdcard/Pictures/Screenshots", it).isFile } ?: 0
        when {
            foreign == 0 && cameraFiles == 0 && screenshotFiles == 0 ->
                appendLine("  ✅ 看不到任何不属于自己的照片/视频（MediaStore 和文件路径都被挡住）")
            foreign == 0 ->
                appendLine("  ⚠️ MediaStore 被挡住了，但文件路径还能列出 相机 $cameraFiles / 截图 $screenshotFiles 个文件")
            else ->
                appendLine("  ❌ 能看到 $foreign 个不属于自己的媒体项")
        }
    }

    /** total, own, sample names (max 5) */
    private fun countMedia(uri: Uri): Triple<Int, Int, List<String>> {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC").use { c ->
            c ?: return Triple(-1, -1, listOf("null cursor"))
            var own = 0
            val sample = ArrayList<String>()
            while (c.moveToNext()) {
                val owner = c.getString(2)
                if (owner == packageName) own++
                if (sample.size < 5) sample += "${c.getString(1)}  (owner=${owner ?: "?"})"
            }
            return Triple(c.count, own, sample)
        }
    }

    // ---------------------------------------------------------------- single probes

    private fun list(path: String): String {
        val f = File(path)
        val names = f.list() ?: return "list() = null (exists=${f.exists()}, isDir=${f.isDirectory})"
        return "count=${names.size}\n" + names.take(40).joinToString("\n")
    }

    private fun readHead(path: String): String {
        val f = File(path)
        FileInputStream(f).use { input ->
            val buf = ByteArray(64)
            val n = input.read(buf)
            return "read $n bytes, size=${f.length()}"
        }
    }

    private fun saveImage(): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "yins_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YinsTest")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return "insert failed"
        contentResolver.openOutputStream(uri)!!.use { out ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val back = contentResolver.openInputStream(uri)?.use { it.read(ByteArray(16)) } ?: -1
        return "saved $uri, 读回 $back 字节\n" + list("/sdcard/Pictures/YinsTest")
    }

    private fun writeCustomDir(): String {
        val dir = File("/sdcard/YinsTest")
        val made = dir.mkdirs()
        val f = File(dir, "hello_${System.currentTimeMillis()}.txt")
        f.writeText("hello")
        return "mkdirs=$made exists=${dir.exists()} wrote=${f.exists()} readBack=${runCatching { f.readText() }.getOrElse { "EXC" }}\n" + list(dir.path)
    }
}
