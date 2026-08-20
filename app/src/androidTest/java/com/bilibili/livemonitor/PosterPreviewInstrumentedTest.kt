package com.bilibili.livemonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.PopularityPointEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.util.StatsImageAssetLoader
import com.bilibili.livemonitor.util.StatsImageDataFactory
import com.bilibili.livemonitor.util.StatsImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/**
 * 海报真机渲染验证：造一个月的逼真数据（多场次/心情/人气/粉丝/魔法期），
 * 走 StatsImageDataFactory + StatsImageRenderer 全链路出图，写到
 * getExternalFilesDir 供 adb pull 人工核对排版。
 * 运行后：adb pull <path>/poster_preview.png
 */
@RunWith(AndroidJUnit4::class)
class PosterPreviewInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
            AppDatabase.get(context).moodEventDao().deleteAll()
        }
        File(context.filesDir, "covers/poster-preview.png").delete()
    }

    @After
    fun tearDown() {
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
            AppDatabase.get(context).moodEventDao().deleteAll()
        }
        File(context.filesDir, "covers/poster-preview.png").delete()
    }

    @Test
    fun generatePosterPreview() = runBlocking {
        val dao = AppDatabase.get(context).streamSessionDao()
        val now = System.currentTimeMillis()
        val monthStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val cover = File(context.filesDir, "covers/poster-preview.png").apply {
            parentFile!!.mkdirs()
            val bitmap = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                drawColor(0xFF6D4BA0.toInt())
                drawCircle(480f, 270f, 150f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFF8C7D8.toInt()
                })
            }
            outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        // 9 场分布在月上旬，标题覆盖中英文
        val titles = listOf("happy", "sc2 肉鸽之心", "sad", "舟个痛快", "happy~", "瞧", "突变有手就行啊（", "吹水喵~", "我们的cndota")
        titles.forEachIndexed { i, title ->
            val start = monthStart + (2L + i) * 86_400_000L + 20 * 3_600_000L
            val sid = dao.insertSession(
                StreamSessionEntity(
                    startTs = start,
                    endTs = start + 150 * 60_000L,
                    title = title,
                    coverPath = cover.absolutePath
                )
            )
            // 每场 3 个人气点（峰值 300~500）
            repeat(3) { j ->
                dao.insertPopularityPoint(
                    PopularityPointEntity(
                        sessionId = sid, ts = start + j * 600_000L,
                        online = 300 + i * 30 + j * 20
                    )
                )
            }
        }
        // 心情事件 4 条
        val moods = listOf("happy" to "看了场直播", "sad" to "失眠", "breakdown" to "睡眠剥夺", "happy" to "和女朋友玩游戏")
        moods.forEachIndexed { i, (mood, title) ->
            AppDatabase.get(context).moodEventDao().insert(
                MoodEventEntity(
                    eventTs = monthStart + (3L + i * 2) * 86_400_000L + 22 * 3_600_000L,
                    durationMin = 45 + i * 15,
                    mood = mood,
                    title = title,
                    reason = "因为发生了值得记住的事情 $i",
                    note = "这是月报里需要展示的备注 $i",
                    createdAt = now
                )
            )
        }
        // 粉丝快照 5 个点
        repeat(5) { i ->
            dao.insertFollowerSnapshot(
                com.bilibili.livemonitor.db.FollowerSnapshotEntity(
                    ts = monthStart + i * 2 * 86_400_000L, followerNum = 22420L + i * 7
                )
            )
        }
        // 魔法期：2~4 日
        PreferenceManager(context).setMagicPeriodsJson(
            org.json.JSONArray().put(
                org.json.JSONObject()
                    .put("start", monthStart + 86_400_000L)
                    .put("end", monthStart + 3 * 86_400_000L)
            ).toString()
        )

        val monthCal = Calendar.getInstance().apply { timeInMillis = monthStart }
        val data = StatsImageDataFactory.build(context, monthCal)
        val posterData = StatsImageAssetLoader.load(context, data)
        val bmp = try {
            StatsImageRenderer.render(context, posterData, avatar = null)
        } finally {
            StatsImageAssetLoader.recycle(posterData)
        }
        val out = File(context.getExternalFilesDir(null), "poster_preview.png")
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.exists() && out.length() > 10_000)
        println("POSTER_PATH=${out.absolutePath}")
        println("POSTER_SIZE=${bmp.width}x${bmp.height} records=${data.records.size}")
    }
}
