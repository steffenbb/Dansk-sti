package com.example.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.example.ui.theme.Motion

/** Screen-change motion (UI audit batch 1): quick fade-through between tabs, a short slide for other screens. */
object ScreenTransitions {
    private val TAB_ROUTES = setOf(Screen.Explore.route, Screen.Map.route, Screen.Saved.route, Screen.Profile.route)

    fun isTabSwitch(from: String?, to: String?): Boolean = from in TAB_ROUTES && to in TAB_ROUTES

    fun enter(from: String?, to: String?): EnterTransition =
        if (isTabSwitch(from, to)) fadeIn(tween(Motion.TAB_MS, delayMillis = Motion.TAB_MS / 2))
        else slideInHorizontally(tween(Motion.SCREEN_MS, easing = Motion.EmphasizedDecelerate)) { it / 10 } +
            fadeIn(tween(Motion.SCREEN_MS, easing = Motion.EmphasizedDecelerate))

    fun exit(from: String?, to: String?): ExitTransition =
        if (isTabSwitch(from, to)) fadeOut(tween(Motion.TAB_MS / 2)) else fadeOut(tween(Motion.EXIT_MS))

    fun popEnter(from: String?, to: String?): EnterTransition =
        if (isTabSwitch(from, to)) fadeIn(tween(Motion.TAB_MS)) else fadeIn(tween(Motion.SCREEN_MS, easing = Motion.EmphasizedDecelerate))

    fun popExit(from: String?, to: String?): ExitTransition =
        if (isTabSwitch(from, to)) fadeOut(tween(Motion.TAB_MS / 2))
        else slideOutHorizontally(tween(Motion.ENTER_MS, easing = Motion.EmphasizedAccelerate)) { it / 10 } +
            fadeOut(tween(Motion.ENTER_MS, easing = Motion.EmphasizedAccelerate))
}
