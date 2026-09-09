package moe.damesck.yins.data

import android.content.Context
import android.content.pm.ApplicationInfo

/** Coarse app category used only to pick which of the four choices to suggest. */
enum class AppKind {
    /** Messaging / productivity / file tools where sending arbitrary photos and files is the point. */
    TRUSTED,

    /** Shopping, food delivery, travel, content platforms: occasionally need one or two photos. */
    TEMPORARY,

    /** Everything else. */
    UNKNOWN,
}

/**
 * Suggests a mode per app. The suggestion is only a default highlighted in the dialog and the
 * manager; the user always chooses.
 */
object Recommendations {
    fun mode(kind: AppKind): Mode = when (kind) {
        AppKind.TRUSTED -> Mode.FULL
        AppKind.TEMPORARY -> Mode.PARTIAL
        AppKind.UNKNOWN -> Mode.BLANK
    }

    fun mode(context: Context, packageName: String): Mode = mode(kind(context, packageName))

    fun kind(context: Context, packageName: String): AppKind {
        kindOf(packageName)?.let { return it }
        // Preinstalled apps (gallery, file manager, camera, vendor clouds...) break badly when
        // masked, and the vendor already had the data anyway.
        return if (isSystemApp(context, packageName)) AppKind.TRUSTED else AppKind.UNKNOWN
    }

    /** Classification by package name alone; null when the package is not in the built-in table. */
    fun kindOf(packageName: String): AppKind? = when (packageName) {
        in TRUSTED -> AppKind.TRUSTED
        in TEMPORARY -> AppKind.TEMPORARY
        else -> null
    }

    private fun isSystemApp(context: Context, packageName: String): Boolean = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (t: Throwable) {
        false
    }

    private val TRUSTED = setOf(
        // IM / collaboration
        "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.qqlite",
        "com.tencent.mm", "com.tencent.wework",
        "com.alibaba.android.rimet", "com.ss.android.lark", "com.ss.android.lark.international",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
        "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms",
        "jp.naver.line.android", "com.Slack", "com.microsoft.teams", "com.discord",
        "com.facebook.orca", "com.skype.raider", "com.viber.voip",
        // Cloud storage / backup / sync
        "com.baidu.netdisk", "com.alicloud.databox", "com.google.android.apps.photos",
        "com.google.android.apps.docs", "com.dropbox.android", "com.microsoft.skydrive",
        "com.nextcloud.client", "com.owncloud.android", "com.synology.DSfile",
        "com.synology.moments", "com.tencent.weiyun", "cn.wps.moffice_eng",
        "com.microsoft.office.officehubrow",
        // File managers / galleries / media tools
        "com.mixplorer", "com.mixplorer.silver", "me.zhanghai.android.files",
        "com.lonelycatgames.Xplore", "nextapp.fx", "com.simplemobiletools.gallery.pro",
        "org.fossify.gallery", "org.fossify.filemanager", "com.simplemobiletools.filemanager.pro",
        "com.termux", "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
        "com.adobe.lrmobile", "com.snapseed", "com.niksoftware.snapseed",
        "com.google.android.apps.nbu.files",
    )

    private val TEMPORARY = setOf(
        // Local services / food
        "com.dianping.v1", "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "me.ele",
        "com.wuba", "com.anjuke.android.app", "com.lianjia.beike", "com.ganji.android",
        // Shopping
        "com.taobao.taobao", "com.taobao.litetao", "com.tmall.wireless", "com.jingdong.app.mall",
        "com.jd.jdlite", "com.xunmeng.pinduoduo", "com.achievo.vipshop", "com.taobao.idlefish",
        "com.suning.mobile.ebuy", "com.dangdang.buy2", "com.smzdm.client.android",
        "com.alibaba.wireless", "com.alibaba.aliexpresshd",
        // Payment / finance
        "com.eg.android.AlipayGphone", "com.unionpay", "com.cmbchina.ccd.pluto.cmbActivity",
        // Content platforms / social feeds
        "com.xingin.xhs", "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite",
        "com.ss.android.ugc.live", "com.smile.gifmaker", "com.kuaishou.nebula",
        "tv.danmaku.bili", "com.bilibili.app.in", "com.sina.weibo", "com.weico.international",
        "com.zhihu.android", "com.douban.frodo", "com.ss.android.article.news",
        "com.ss.android.article.lite", "com.tencent.news", "com.netease.newsreader.activity",
        "com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically",
        "com.reddit.frontpage", "com.pinterest",
        // Travel / transport / maps
        "com.sdu.didi.psnger", "com.autonavi.minimap", "com.baidu.BaiduMap", "com.MobileTicket",
        "ctrip.android.view", "com.Qunar", "com.tongcheng.android", "com.taobao.trip",
        "com.mfw.roadbook", "com.jingyao.easybike", "com.tencent.map",
        // Music / audio / video streaming
        "com.netease.cloudmusic", "com.tencent.qqmusic", "com.kugou.android", "com.kuwo.player",
        "com.ximalaya.ting.android", "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone",
        "com.hunantv.imgo.activity", "com.spotify.music", "com.google.android.youtube",
        // Browsers / search
        "com.baidu.searchbox", "com.UCMobile", "com.tencent.mtt", "com.quark.browser",
        "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
        // Games with photo upload / misc
        "com.tencent.tmgp.sgame", "com.tencent.tmgp.pubgmhd", "com.miHoYo.Yuanshen",
        "com.miHoYo.hkrpg", "com.duowan.kiwi", "air.tv.douyu.android",
    )
}
