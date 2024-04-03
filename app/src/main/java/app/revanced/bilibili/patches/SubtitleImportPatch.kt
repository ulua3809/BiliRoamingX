package app.revanced.bilibili.patches

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.annotation.Keep
import androidx.documentfile.provider.DocumentFile
import app.revanced.bilibili.patches.SubtitleImportPatch.HookInfoProvider.getDanmakuParamsMethod
import app.revanced.bilibili.patches.SubtitleImportPatch.HookInfoProvider.getDmViewReplyMethod
import app.revanced.bilibili.patches.SubtitleImportPatch.HookInfoProvider.hideWidgetMethod
import app.revanced.bilibili.patches.SubtitleImportPatch.HookInfoProvider.loadSubtitleMethod
import app.revanced.bilibili.patches.SubtitleImportPatch.HookInfoProvider.setDmViewReplyMethod
import app.revanced.bilibili.patches.SubtitleImportPatch.HookInfoProvider.widgetTokenField
import app.revanced.bilibili.patches.okhttp.hooks.Subtitle
import app.revanced.bilibili.settings.Settings
import app.revanced.bilibili.utils.*
import com.bapis.bilibili.community.service.dm.v1.DmViewReply
import com.bapis.bilibili.community.service.dm.v1.SubtitleItem
import kotlin.random.Random

object SubtitleImportPatch {
    private val supportedSubExt = arrayOf("ass", "srt", "vtt", "json")
    private var unique = 1

    private fun readAndConvertSubtitle(uri: Uri, type: String) = runCatching {
        Utils.getContext().contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() } ?: error("read failed")
    }.onFailure {
        Utils.runOnMainThread(800L) {
            Toasts.showShort("字幕导入失败，无法读取")
        }
        LogHelper.error({ "Failed to read subtitle, type: $type, uri: $uri" }, it)
    }.mapCatching {
        when (type) {
            "ass" -> SubtitleHelper.ass2Bcc(it)
            "srt", "vtt" -> SubtitleHelper.vttOrSrt2Bcc(it)
            "json" -> it
            else -> error("unsupported")
        }
    }.onFailure {
        Utils.runOnMainThread(800L) {
            Toasts.showShort("字幕导入失败，无法转换")
        }
        LogHelper.error({ "Failed to convert subtitle, type: $type, uri: $uri" }, it)
    }.getOrNull()

    @Keep
    @JvmStatic
    @SuppressLint("InlinedApi")
    fun onCreateSubtitleWidget(widget: Any, view: View) {
        val importButton = view.findView<View>("biliroaming_import_subtitle")
        if (!Settings.SUBTITLE_IMPORT.boolean) {
            importButton.visibility = View.GONE
            return
        }
        importButton.visibility = View.VISIBLE
        val interactLayerService = widget.getObjectField(
            "interactLayerServiceForBiliRoaming"
        ) ?: return
        val widgetService = widget.getObjectField(
            "widgetServiceForBiliRoaming"
        ) ?: return
        importButton.setOnClickListener { button ->
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/x-subrip", "application/octet-stream", "application/json")
                )
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            (button.context as Activity).launchCatching(intent) { code, data ->
                val uri = data?.data
                if (code != Activity.RESULT_OK || uri == null) return@launchCatching
                val type = (DocumentFile.fromSingleUri(Utils.getContext(), uri)?.name
                    ?: uri.toString()).substringAfterLast('.').lowercase()
                if (!supportedSubExt.contains(type)) {
                    Utils.runOnMainThread(800L) {
                        Toasts.showShort("字幕导入失败，格式不支持")
                    }
                    return@launchCatching
                }
                val dmViewReply = interactLayerService.callMethod(getDanmakuParamsMethod)
                    ?.callMethodAs<DmViewReply>(getDmViewReplyMethod) ?: return@launchCatching
                val newDmViewReply = DmViewReply.parseFrom(dmViewReply.toByteArray())
                val subtitle = newDmViewReply.subtitle
                if (subtitle.subtitlesList.none { it.lan.startsWith("import") }) {
                    Subtitle.importedSubtitles.clear()
                    unique = 1
                }
                readAndConvertSubtitle(uri, type)?.let {
                    Subtitle.importedSubtitles[unique] = it
                } ?: return@launchCatching
                val newSubtitle = SubtitleItem().apply {
                    val randomId = Random.nextLong()
                    id = randomId
                    idStr = randomId.toString()
                    lan = "import${unique}"
                    lanDoc = "漫游导入${unique}"
                    lanDocBrief = "导入"
                    val url = subtitle.subtitlesList.first().subtitleUrl
                    subtitleUrl = Uri.parse(url).buildUpon()
                        .appendQueryParameter("zh_converter", "import")
                        .appendQueryParameter("import_unique", unique.toString())
                        .toString()
                }
                unique++
                subtitle.addSubtitles(newSubtitle)
                interactLayerService.callMethod(setDmViewReplyMethod, newDmViewReply)
                interactLayerService.callMethod(loadSubtitleMethod, newSubtitle, null)
                widgetService.callMethod(hideWidgetMethod, widget.getObjectField(widgetTokenField))
                Utils.runOnMainThread(800L) {
                    Toasts.showShort("字幕导入成功")
                }
            }.onFailure {
                Toasts.showShortWithId("biliroaming_pls_install_file_manager")
            }
        }
    }

    object HookInfoProvider {
        @Keep
        @JvmStatic
        var getDanmakuParamsMethod = ""

        @Keep
        @JvmStatic
        var getDmViewReplyMethod = ""

        @Keep
        @JvmStatic
        var setDmViewReplyMethod = ""

        @Keep
        @JvmStatic
        var loadSubtitleMethod = ""

        @Keep
        @JvmStatic
        var hideWidgetMethod = ""

        @Keep
        @JvmStatic
        var widgetTokenField = ""

        init {
            init()
        }

        @Keep
        @JvmStatic
        private fun init() {
        }
    }
}