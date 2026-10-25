package com.swufe.homework.ui.nav

import kotlinx.serialization.Serializable

/**
 * 全部导航目的地（Navigation Compose 类型安全路由）。
 * 约定见 android/NAVIGATION.md：WS4 负责账号、课表、我的；WS5 负责首页 [Home] 与班级页 [ClassPage]。
 */
object Routes {
    // ---- 账号（WS4）----
    @Serializable data object Login
    @Serializable data object Register
    @Serializable data object Recover
    /** 注册或找回后强制展示恢复码。fromRecover=true 时确认后回登录页。 */
    @Serializable data class RecoveryCode(val code: String, val fromRecover: Boolean = false)

    // ---- 主界面（底部导航）----
    @Serializable data object Main

    // ---- 课表（WS4）----
    @Serializable data object TimetableImport
    @Serializable data object TimetableManual

    // ---- 班级页（WS5 实现内容；WS4 提供「不是我的班」与班级信息头部）----
    @Serializable data class ClassPage(val classId: Int)

    // ---- 作业（WS5）----
    @Serializable data class AssignmentDetail(val id: Int)
    /** 发布：只给 classId；补充：再给 assignmentId；编辑自己的来源：再给 sourceId。-1 表示无。 */
    @Serializable data class Publish(val classId: Int, val assignmentId: Int = -1, val sourceId: Int = -1)
    /** WS11：首发者编辑作业（标题、DDL、说明）。 */
    @Serializable data class EditAssignment(val id: Int)
    @Serializable data object MyPoints
    @Serializable data object PointsRules
    @Serializable data object ReminderGuide

    // ---- 我的（WS4）----
    @Serializable data object Settings
    @Serializable data object Invites

    // ---- 管理（WS11，v2）：只有 /me.role=admin 可进，导航图里用 AdminGate 再拦一次 ----
    @Serializable data object Admin
    @Serializable data class AdminUserDetail(val id: Int)
}

/** 底部导航的三个 tab。WS5 实现 Home 的内容。 */
enum class MainTab(val label: String) { Home("首页"), Timetable("课表"), Me("我的") }
