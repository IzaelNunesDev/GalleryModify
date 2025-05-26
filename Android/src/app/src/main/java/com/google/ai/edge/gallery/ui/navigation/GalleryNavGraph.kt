/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.navigation

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.data.Model
// TASK_IMAGE_CLASSIFICATION removed
// TASK_IMAGE_GENERATION removed
// TASK_LLM_CHAT removed
import com.google.ai.edge.gallery.data.TASK_LLM_ASK_IMAGE // Keep this
// TASK_LLM_PROMPT_LAB removed
// TASK_TEXT_CLASSIFICATION removed
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.TaskType
import com.google.ai.edge.gallery.data.getModelByName
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.home.HomeScreen
// ImageClassificationDestination removed
// ImageClassificationScreen removed
// ImageGenerationDestination removed
// ImageGenerationScreen removed
// LlmChatDestination removed
// LlmChatScreen removed
import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageDestination // Keep this
import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageScreen // Keep this
// LlmSingleTurnDestination removed
// LlmSingleTurnScreen removed
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
// TextClassificationDestination removed
// TextClassificationScreen removed

private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_PLACEHOLDER = "placeholder"
private const val ENTER_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private const val ENTER_ANIMATION_DELAY_MS = 100

private const val EXIT_ANIMATION_DURATION_MS = 500
private val EXIT_ANIMATION_EASING = EaseOutExpo

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
    delayMillis = ENTER_ANIMATION_DELAY_MS
  )
}

private fun exitTween(): FiniteAnimationSpec<IntOffset> {
  return tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
}

private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
  )
}

private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Right,
  )
}

/**
 * Navigation routes.
 */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel = viewModel(factory = ViewModelProvider.Factory)
) {
  var showModelManager by remember { mutableStateOf(false) }
  var pickedTask by remember { mutableStateOf<Task?>(null) }

  HomeScreen(
    modelManagerViewModel = modelManagerViewModel,
    navigateToTaskScreen = { task ->
      pickedTask = task
      showModelManager = true
    },
  )

  // Model manager.
  AnimatedVisibility(
    visible = showModelManager,
    enter = slideInHorizontally(initialOffsetX = { it }),
    exit = slideOutHorizontally(targetOffsetX = { it }),
  ) {
    val curPickedTask = pickedTask
    if (curPickedTask != null) {
      ModelManager(viewModel = modelManagerViewModel,
        task = curPickedTask,
        onModelClicked = { model ->
          navigateToTaskScreen(
            navController = navController, taskType = curPickedTask.type, model = model
          )
        },
        navigateUp = { showModelManager = false })
    }
  }

  NavHost(
    navController = navController,
    // Default to open home screen.
    startDestination = ROUTE_PLACEHOLDER,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    modifier = modifier.zIndex(1f)
  ) {
    // Placeholder root screen
    composable(
      route = ROUTE_PLACEHOLDER,
    ) {
      Text("")
    }

    // LLM image to text.
    composable(
      route = "${LlmAskImageDestination.route}/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      // In the simplified Tasks.kt, TASK_LLM_ASK_IMAGE is the only task.
      // Its models list is initially empty, models are added via ModelManager.
      // So, getModelFromNavigationParam will rely on modelName being present
      // or handle the case where task.models might be empty if modelName is not provided.
      // Forcing it to use TASK_LLM_ASK_IMAGE here.
      getModelFromNavigationParam(it, TASK_LLM_ASK_IMAGE)?.let { defaultModel ->
        modelManagerViewModel.selectModel(defaultModel)

        LlmAskImageScreen(
          modelManagerViewModel = modelManagerViewModel,
          navigateUp = { navController.navigateUp() },
        )
      }
    }
  }

  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  if (data != null) {
    intent.data = null
    Log.d(TAG, "navigation link clicked: $data")
    if (data.toString().startsWith("com.google.ai.edge.gallery://model/")) {
      val modelName = data.pathSegments.last()
      getModelByName(modelName)?.let { model ->
        // TODO(jingjin): need to show a list of possible tasks for this model.
        navigateToTaskScreen(
          navController = navController, taskType = TaskType.LLM_ASK_IMAGE, model = model
        )
      }
    }
  }
}

fun navigateToTaskScreen(
  navController: NavHostController, taskType: TaskType, model: Model? = null
) {
  val modelName = model?.name ?: ""
  // All task types now navigate to LlmAskImageDestination
  if (taskType == TaskType.LLM_ASK_IMAGE) {
    navController.navigate("${LlmAskImageDestination.route}/${modelName}")
  } else {
    // Optionally, log or handle other task types if they appear unexpectedly
    Log.w(TAG, "navigateToTaskScreen called with unexpected taskType: $taskType, navigating to LLM_ASK_IMAGE by default.")
    navController.navigate("${LlmAskImageDestination.route}/${modelName}")
  }
}

fun getModelFromNavigationParam(entry: NavBackStackEntry, task: Task): Model? {
  var modelName = entry.arguments?.getString("modelName") ?: ""
  if (modelName.isEmpty()) {
    modelName = task.models[0].name
  }
  val model = getModelByName(modelName)
  return model
}
