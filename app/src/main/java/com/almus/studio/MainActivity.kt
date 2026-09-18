package com.almus.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.almus.studio.ui.screens.HomeScreen
import com.almus.studio.ui.screens.NewProjectScreen
import com.almus.studio.ui.screens.SettingsScreen
import com.almus.studio.ui.screens.StudioScreen
import com.almus.studio.ui.theme.AlmusStudioTheme
import com.almus.studio.viewmodel.StudioViewModel

private object Routes {
    const val HOME = "home"
    const val NEW_PROJECT = "new_project"
    const val STUDIO = "studio"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlmusStudioTheme {
                AlmusNavHost(viewModel)
            }
        }
    }
}

@Composable
private fun AlmusNavHost(viewModel: StudioViewModel) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onOpenProject = { projectId ->
                    viewModel.openProject(projectId)
                    navController.navigate(Routes.STUDIO)
                },
                onNewProject = { navController.navigate(Routes.NEW_PROJECT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.NEW_PROJECT) {
            NewProjectScreen(
                onCreate = { name, bpm ->
                    viewModel.createProject(name, bpm)
                    navController.navigate(Routes.STUDIO) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Routes.STUDIO) {
            StudioScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.closeProject()
                    navController.popBackStack()
                }
            )
        }
    }
}
