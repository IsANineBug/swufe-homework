package com.swufe.homework

import com.swufe.homework.data.api.AdminAction
import com.swufe.homework.data.api.AdminApi
import com.swufe.homework.data.api.AdminClass
import com.swufe.homework.data.api.AdminDeletedSource
import com.swufe.homework.data.api.AdminReport
import com.swufe.homework.data.api.AdminUser
import com.swufe.homework.data.api.ClassMergeRecord
import com.swufe.homework.data.api.Me
import com.swufe.homework.data.api.Message
import com.swufe.homework.data.api.ReportClassTarget
import com.swufe.homework.data.api.ReportContextMessage
import com.swufe.homework.data.api.ReportMessageTarget
import com.swufe.homework.data.api.ReportReporter
import com.swufe.homework.data.api.ReportSourceTarget
import com.swufe.homework.data.api.Sanction
import com.swufe.homework.data.api.Source
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.repo.AccountRepository
import com.swufe.homework.ui.admin.AdminAccess
import com.swufe.homework.ui.admin.AdminGateViewModel
import com.swufe.homework.ui.admin.AdminRepository
import com.swufe.homework.ui.admin.ReportAction
import com.swufe.homework.ui.admin.SanctionKind
import com.swufe.homework.ui.admin.adminAccess
import com.swufe.homework.ui.admin.adminReasonValid
import com.swufe.homework.ui.admin.announcementEffectiveAt
import com.swufe.homework.ui.admin.authorNickname
import com.swufe.homework.ui.admin.buildSanction
import com.swufe.homework.ui.admin.className
import com.swufe.homework.ui.admin.exactUser
import com.swufe.homework.ui.admin.messageTarget
import com.swufe.homework.ui.admin.pointsAdjustValid
import com.swufe.homework.ui.admin.reportActions
import com.swufe.homework.ui.admin.resolutionFor
import com.swufe.homework.ui.admin.sanctionDayOptions
import com.swufe.homework.ui.admin.showAdminEntry
import com.swufe.homework.ui.admin.userTarget
import com.swufe.homework.ui.chat.ReportKind
import com.swufe.homework.ui.chat.userReportTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** WS11 第 6 条（管理页面）与第 5 条遗留（举报这个人）。 */
@OptIn(ExperimentalCoroutinesApi::class)
/** 固定时间（拆开写，避免被工具改写格式）。 */
private const val TS = "2026-10-25" + "T12:13:00+08:00"

class Ws11AdminTest {
    /** 与 di/AppModule.json() 相同的配置。 */
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { server.shutdown(); Dispatchers.resetMain() }

    private fun retrofit(): Retrofit = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
        .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val user = Me(id = 1, nickname = "甲", role = "user")
    private val admin = Me(id = 2, nickname = "管", role = "admin")

    // ---------- 入口与路由拦截 ----------

    @Test fun adminEntryOnlyForAdmins() {
        assertFalse(showAdminEntry(null))
        assertFalse(showAdminEntry(user))
        assertTrue(showAdminEntry(admin))
    }

    @Test fun nonAdminIsBlockedAtRoute() {
        // 缓存或最新的 /me 说不是管理员 → 拒绝
        assertEquals(AdminAccess.Denied, adminAccess(cached = user, fresh = null, freshFailed = false))
        assertEquals(AdminAccess.Denied, adminAccess(cached = null, fresh = user, freshFailed = false))
        // 缓存是管理员但服务器最新说已不是 → 以服务器为准，拒绝
        assertEquals(AdminAccess.Denied, adminAccess(cached = admin, fresh = user, freshFailed = false))
        // 没有缓存、拉取失败 → 不放行
        assertEquals(AdminAccess.Denied, adminAccess(cached = null, fresh = null, freshFailed = true))
        // 还没结果 → 检查中（不显示任何管理内容）
        assertEquals(AdminAccess.Checking, adminAccess(cached = null, fresh = null, freshFailed = false))
        assertEquals(AdminAccess.Allowed, adminAccess(cached = null, fresh = admin, freshFailed = false))
        assertEquals(AdminAccess.Allowed, adminAccess(cached = admin, fresh = null, freshFailed = false))
    }

    /** 路由门的 ViewModel：进入时重新拉 /me，普通用户（即使是深链直接进来）被拦。 */
    @Test fun gateViewModelDeniesNonAdminFromServer() {
        server.enqueue(MockResponse().setBody("""{"id":1,"nickname":"甲","role":"user"}"""))
        val account = AccountRepository(retrofit().create(SwufeApi::class.java))
        val vm = AdminGateViewModel(account)
        waitUntil { vm.access != AdminAccess.Checking }
        assertEquals(AdminAccess.Denied, vm.access)
        assertEquals("/api/v1/me", server.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test fun gateViewModelAllowsAdmin() {
        server.enqueue(MockResponse().setBody("""{"id":2,"nickname":"管","role":"admin"}"""))
        val vm = AdminGateViewModel(AccountRepository(retrofit().create(SwufeApi::class.java)))
        waitUntil { vm.access != AdminAccess.Checking }
        assertEquals(AdminAccess.Allowed, vm.access)
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    // ---------- 原因必填 ----------

    @Test fun reasonRequiredForDestructiveActions() {
        assertFalse("原因为空不能确认", adminReasonValid(""))
        assertFalse("只有空白也不行", adminReasonValid("   \n "))
        assertTrue(adminReasonValid("广告"))
        assertTrue(adminReasonValid("字".repeat(200)))
        assertFalse(adminReasonValid("字".repeat(201)))
    }

    @Test fun sanctionNeedsReasonAndDays() {
        assertNull(buildSanction(SanctionKind.Mute, 7, ""))
        assertNull("禁言必须给天数", buildSanction(SanctionKind.Mute, null, "刷屏"))
        assertNull("禁发必须给天数", buildSanction(SanctionKind.PostBan, null, "乱发"))
        assertNull(buildSanction(SanctionKind.Mute, 0, "刷屏"))
        assertNull(buildSanction(SanctionKind.Mute, 366, "刷屏"))
        val perm = buildSanction(SanctionKind.Disable, null, " 广告号 ")!!
        assertEquals("disable", perm.kind); assertNull(perm.days); assertEquals("广告号", perm.reason)
        assertEquals(7, buildSanction(SanctionKind.PostBan, 7, "乱发")!!.days)
        assertTrue(null in sanctionDayOptions(SanctionKind.Disable))
        assertFalse(null in sanctionDayOptions(SanctionKind.Mute))
    }

    @Test fun pointsAdjustValidation() {
        assertFalse("必须选班", pointsAdjustValid(null, 5, "补偿"))
        assertFalse("不能为 0", pointsAdjustValid(1, 0, "补偿"))
        assertFalse(pointsAdjustValid(1, 101, "补偿"))
        assertFalse(pointsAdjustValid(1, -101, "补偿"))
        assertFalse("原因必填", pointsAdjustValid(1, 5, " "))
        assertTrue(pointsAdjustValid(1, -100, "刷分"))
        assertTrue(pointsAdjustValid(1, 100, "补偿"))
    }

    @Test fun announcementAtLeastSevenDays() {
        val now = ZonedDateTime.of(2026, 10, 25, 19, 13, 0, 0, java.time.ZoneId.of("Asia/Shanghai"))
        for (d in listOf(1L, 7L, 14L)) {
            val at = announcementEffectiveAt(now, d)
            assertTrue(Duration.between(now, at) >= Duration.ofDays(7))
            assertEquals(0, at.minute)
        }
    }

    // ---------- 隐私：模型只含 api.yaml 列出的字段 ----------

    private fun names(d: SerialDescriptor) = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()

    @Test fun adminUserHasOnlyContractFields() {
        assertEquals(
            setOf("id", "nickname", "role", "registered_via", "inviter_nickname", "created_at", "disabled",
                "contribution_total", "deleted_content_30d", "reports_against_open", "sanctions"),
            names(AdminUser.serializer().descriptor),
        )
    }

    @Test fun noAdminModelCarriesPrivateData() {
        val forbidden = listOf("password", "recovery", "token", "ip", "classes", "class_ids", "timetable", "slots", "done", "reminder", "ai_job")
        val all: List<KSerializer<*>> = listOf(
            AdminUser.serializer(), Sanction.serializer(), AdminClass.serializer(), AdminReport.serializer(),
            ReportReporter.serializer(), ReportSourceTarget.serializer(), ReportMessageTarget.serializer(),
            ReportContextMessage.serializer(), ReportClassTarget.serializer(), ClassMergeRecord.serializer(),
            AdminDeletedSource.serializer(), AdminAction.serializer(),
        )
        for (s in all) for (n in names(s.descriptor)) for (f in forbidden) {
            assertFalse("${s.descriptor.serialName}.$n 看起来是隐私字段（$f）", n == f || n.startsWith("${f}_") || n.endsWith("_$f"))
        }
    }

    @Test fun extraServerFieldsAreDroppedFromAdminUser() {
        // 即使服务器误多给了字段，解码后也不会进入界面
        val u = json.decodeFromString(
            AdminUser.serializer(),
            """{"id":3,"nickname":"乙","role":"user","registered_via":"campus","password_hash":"x","recovery_code":"y",
               "ip":"1.2.3.4","classes":[1,2],"timetable":[],"done":[5],"reminders":[],"sanctions":[]}""",
        )
        val keys = json.parseToJsonElement(json.encodeToString(AdminUser.serializer(), u)).jsonObject.keys
        for (k in listOf("password_hash", "recovery_code", "ip", "classes", "timetable", "done", "reminders")) assertFalse(k, k in keys)
    }

    /** 管理员不能浏览频道：AdminApi 里没有任何 GET 消息的接口，所有路径都在 admin/ 下。 */
    @Test fun adminApiCannotBrowseChannels() {
        val gets = AdminApi::class.java.declaredMethods.mapNotNull { it.getAnnotation(GET::class.java)?.value }
        assertTrue(gets.isNotEmpty())
        assertTrue(gets.none { "messages" in it })
        val all = AdminApi::class.java.declaredMethods.flatMap { m -> m.annotations.toList() }.mapNotNull { a ->
            when (a) {
                is GET -> a.value; is retrofit2.http.POST -> a.value; is retrofit2.http.PATCH -> a.value
                is retrofit2.http.HTTP -> a.path; else -> null
            }
        }
        assertTrue(all.all { it.startsWith("admin/") })
    }

    // ---------- 举报：解析与处置 ----------

    private val messageReport = """{"id":9,"target_kind":"message","target_id":50,
        "target":{"body":"广告","author_nickname":"丙","class_name":"高数","created_at":"$TS",
          "context":[{"id":48,"author_nickname":"甲","body":"早"},{"id":51,"author_nickname":"乙","body":"别发广告"}]},
        "reporters":[{"nickname":"甲","reason":"广告","created_at":"$TS"}],"handled_at":null,"resolution":null}"""

    @Test fun decodesMessageReportWithContext() {
        val r = json.decodeFromString(AdminReport.serializer(), messageReport)
        val t = r.messageTarget()!!
        assertEquals(2, t.context.size)
        assertEquals("丙", r.authorNickname())
        assertEquals("高数", r.className())
        assertNull(r.userTarget())
        assertEquals(
            listOf(ReportAction.DeleteContent, ReportAction.HideClass, ReportAction.Punish, ReportAction.Dismiss),
            reportActions(r),
        )
    }

    @Test fun reportActionsPerKind() {
        val cls = json.decodeFromString(AdminReport.serializer(),
            """{"id":1,"target_kind":"class","target_id":7,"target":{"name":"x","member_count":1,"assignment_count":0,"created_by_nickname":"a"}}""")
        assertEquals(listOf(ReportAction.HideClass, ReportAction.Dismiss), reportActions(cls))
        val usr = json.decodeFromString(AdminReport.serializer(),
            """{"id":2,"target_kind":"user","target_id":3,"target":{"id":3,"nickname":"乙"}}""")
        assertEquals(listOf(ReportAction.Punish, ReportAction.Dismiss), reportActions(usr))
        assertEquals("乙", usr.userTarget()?.nickname)
        val gone = json.decodeFromString(AdminReport.serializer(), """{"id":3,"target_kind":"source","target_id":4,"target":null}""")
        assertEquals(listOf(ReportAction.Dismiss), reportActions(gone))
        val handled = json.decodeFromString(AdminReport.serializer(), messageReport).copy(handledAt = TS)
        assertTrue("已处理的不再给操作", reportActions(handled).isEmpty())
        assertEquals("dismissed", resolutionFor(ReportAction.Dismiss))
        assertEquals("removed", resolutionFor(ReportAction.Punish))
    }

    @Test fun exactNicknameMatch() {
        val l = listOf(AdminUser(1, "小明同学"), AdminUser(2, "小明"))
        assertEquals(2, exactUser(l, "小明")?.id)
        assertNull(exactUser(l, "小"))
    }

    /** 处置后自动 resolve：删除消息（DELETE 带 reason 请求体）→ POST resolve removed。 */
    @Test fun deleteMessageThenResolveSendsReasons() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val repo = AdminRepository(retrofit().create(AdminApi::class.java))
        assertTrue(repo.deleteMessage(50, " 广告 ").isSuccess)
        assertTrue(repo.resolve(9, "removed").isSuccess)
        val del = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("DELETE", del.method)
        assertEquals("/api/v1/admin/messages/50", del.path)
        assertEquals("""{"reason":"广告"}""", del.body.readUtf8())
        val res = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/admin/reports/9/resolve", res.path)
        assertEquals("""{"resolution":"removed"}""", res.body.readUtf8())
    }

    @Test fun adminDeleteSourceSendsPenalize() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val repo = AdminRepository(retrofit().create(AdminApi::class.java))
        assertTrue(repo.deleteSource(4, "虚假", penalize = false).isSuccess)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("DELETE", req.method)
        assertEquals("""{"reason":"虚假","penalize":false}""", req.body.readUtf8())
    }

    @Test fun forbiddenSurfacesAsError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbidden","message":"需要管理员权限"}"""))
        val repo = AdminRepository(retrofit().create(AdminApi::class.java))
        val e = repo.reports("open").exceptionOrNull() as com.swufe.homework.data.api.ApiException
        assertEquals("forbidden", e.code)
        assertEquals("/api/v1/admin/reports?status=open", server.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    // ---------- 第 5 条遗留：举报这个人 ----------

    @Test fun reportUserTargetNeedsAuthorId() {
        assertNull("服务器没带 author_id 时不显示入口", userReportTarget(null, "乙", isMine = false))
        assertNull("不能举报自己", userReportTarget(3, "我", isMine = true))
        val t = userReportTarget(3, "乙", isMine = false)!!
        assertEquals(ReportKind.User, t.kind)
        assertEquals("user", t.kind.api)
        assertEquals(3L, t.id)
    }

    @Test fun authorIdIsOptionalOnSourceAndMessage() {
        val s = json.decodeFromString(Source.serializer(), """{"id":1,"author_nickname":"乙","author_id":3}""")
        assertEquals(3, s.authorId)
        assertNull(json.decodeFromString(Source.serializer(), """{"id":1,"author_nickname":"乙"}""").authorId)
        val m = json.decodeFromString(Message.serializer(), """{"id":5,"class_id":1,"author_id":3,"author_nickname":"乙","body":"hi"}""")
        assertEquals(3, m.authorId)
        assertNull(json.decodeFromString(Message.serializer(), """{"id":5,"body":"hi"}""").authorId)
    }

    // ---------- 文案 ----------

    @Test fun adminUiUsesNewWording() {
        val dirs = listOf(File("src/main/java/com/swufe/homework/ui/admin"), File("src/main/java/com/swufe/homework/ui/chat"))
        val files = dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".kt") }?.toList().orEmpty() }
        assertNotNull(files.firstOrNull())
        for (f in files) {
            val text = f.readText()
            for (w in listOf("积分", "闹钟", "超管")) assertFalse("${f.name} 里出现了「$w」", w in text)
        }
    }
}
