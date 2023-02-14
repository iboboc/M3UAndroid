package com.m3u.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavDestination
import com.m3u.app.navigation.Destination
import com.m3u.app.navigation.M3UNavHost
import com.m3u.features.feed.navigation.feedRoute
import com.m3u.features.live.navigation.liveRoute
import com.m3u.ui.components.AppTopBar
import com.m3u.ui.components.Background
import com.m3u.ui.components.IconButton
import com.m3u.ui.model.LocalTheme

@OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun App(
    appState: AppState = rememberAppState()
) {
    Background {
        Scaffold(
            modifier = Modifier.semantics {
                testTagsAsResourceId = true
            },
            backgroundColor = Color.Transparent,
            contentColor = LocalTheme.current.onBackground,
            bottomBar = {}
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal
                        )
                    )
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val topLevelLabel = appState.currentTopLevelDestination
                        ?.titleTextId
                        ?.let { stringResource(it) }
                    val label by appState.label
                    val text by remember(topLevelLabel) {
                        derivedStateOf { topLevelLabel ?: label }
                    }
                    val isSystemBarVisible =
                        !appState.currentNavDestination.isInDestination<Destination.Live>()
                    AppTopBar(
                        text = text,
                        visible = isSystemBarVisible,
                        actions = {
                            val actions by appState.appActions
                            actions.forEach { action ->
                                IconButton(
                                    icon = action.icon,
                                    contentDescription = action.contentDescription,
                                    onClick = action.onClick
                                )
                            }
                        },
                        onBackPressed =
                        if (appState.currentTopLevelDestination == null) appState::onBackClick
                        else null
                    ) { padding ->
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            M3UNavHost(
                                navController = appState.navController,
                                navigateToDestination = appState::navigateToDestination,
                                setAppActions = appState.setAppActions,
                                playerRect = appState.playerRect,
                                modifier = Modifier
                                    .padding(padding)
                                    .weight(1f)
                            )
                            AnimatedVisibility(
                                visible = isSystemBarVisible,
                            ) {
                                BottomNavigationSheet(
                                    destinations = appState.topLevelDestinations,
                                    navigateToTopLevelDestination = appState::navigateToTopLevelDestination,
                                    currentNavDestination = appState.currentNavDestination,
                                    modifier = Modifier.testTag("BottomNavigationSheet")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

inline fun <reified D : Destination> NavDestination?.isInDestination(): Boolean {
    val targetRoute = when (D::class.java.name) {
        Destination.Live::class.java.name -> liveRoute
        Destination.Feed::class.java.name -> feedRoute
        else -> return false
    }
    return this?.route == targetRoute
}