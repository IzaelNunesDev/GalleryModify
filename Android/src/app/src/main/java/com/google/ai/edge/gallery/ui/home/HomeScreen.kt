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

package com.google.ai.edge.gallery.ui.home

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.google.ai.edge.gallery.services.ScreenTranslatorService
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.ImportedModelInfo
import com.google.ai.edge.gallery.data.Task // Task might still be needed for navigateToTaskScreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGHomeScreen"
// TASK_COUNT_ANIMATION_DURATION, MAX_TASK_CARD_PADDING, etc. are removed

/** Navigation destination data */
object HomeScreenDestination {
  @StringRes
  val titleRes = R.string.app_name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  modifier: Modifier = Modifier
) {
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
  val uiState by modelManagerViewModel.uiState.collectAsState()
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showImportModelSheet by remember { mutableStateOf(false) }
  var showUnsupportedFileTypeDialog by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState()
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  val selectedLocalModelFileUri = remember { mutableStateOf<Uri?>(null) }
  val selectedImportedModelInfo = remember { mutableStateOf<ImportedModelInfo?>(null) }
  val coroutineScope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  val mediaProjectionResultLauncher = rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
      if (result.resultCode == Activity.RESULT_OK && result.data != null) {
          val serviceIntent = Intent(context, ScreenTranslatorService::class.java).apply {
              putExtra(ScreenTranslatorService.EXTRA_RESULT_CODE, result.resultCode)
              putExtra(ScreenTranslatorService.EXTRA_DATA_INTENT, result.data)
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              context.startForegroundService(serviceIntent)
          } else {
              context.startService(serviceIntent)
          }
      } else {
          Toast.makeText(context, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
      }
  }

  val filePickerLauncher: ActivityResultLauncher<Intent> = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.data?.let { uri ->
        val fileName = getFileName(context = context, uri = uri)
        Log.d(TAG, "Selected file: $fileName")
        if (fileName != null && !fileName.endsWith(".task")) {
          showUnsupportedFileTypeDialog = true
        } else {
          selectedLocalModelFileUri.value = uri
          showImportDialog = true
        }
      } ?: run {
        Log.d(TAG, "No file selected or URI is null.")
      }
    } else {
      Log.d(TAG, "File picking cancelled.")
    }
  }

  Scaffold(modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = {
    GalleryTopAppBar(
      title = stringResource(HomeScreenDestination.titleRes),
      rightAction = AppBarAction(actionType = AppBarActionType.APP_SETTING, actionFn = {
        showSettingsDialog = true
      }),
      scrollBehavior = scrollBehavior,
    )
  }, floatingActionButton = {
    // A floating action button to show "import model" bottom sheet.
    SmallFloatingActionButton(
      onClick = {
        showImportModelSheet = true
      },
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.secondary,
    ) {
      Icon(Icons.Filled.Add, "")
    }
  }) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
      ) {
        Text("Loading OCR Translator...")
        Button(onClick = {
          val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
          mediaProjectionResultLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }) {
          Text("Start Translator Service")
        }
        Button(onClick = {
          val intent = Intent(context, ScreenTranslatorService::class.java)
          context.stopService(intent)
        }) {
          Text("Stop Translator Service")
        }
        Button(onClick = {
          ScreenTranslatorService.isProcessingEnabled = !ScreenTranslatorService.isProcessingEnabled
          Toast.makeText(context, "Processing ${if (ScreenTranslatorService.isProcessingEnabled) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }) {
            Text("Toggle Processing (On/Off)")
        }
      }
      // SnackbarHost can remain if global messages are still desired,
      // or be removed if it was only for task-list related operations.
      // For now, let's keep it as it might be used by the import functionality.
      SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(bottom = 32.dp).align(Alignment.BottomCenter))
    }
  }

  // Settings dialog.
  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
    )
  }

  // Import model bottom sheet.
  if (showImportModelSheet) {
    ModalBottomSheet(
      onDismissRequest = { showImportModelSheet = false },
      sheetState = sheetState,
    ) {
      Text(
        "Import model",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
      )
      Box(modifier = Modifier.clickable {
        coroutineScope.launch {
          // Give it sometime to show the click effect.
          delay(200)
          showImportModelSheet = false

          // Show file picker.
          val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Single select.
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
          }
          filePickerLauncher.launch(intent)
        }
      }) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        ) {
          Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = "")
          Text("From local model file")
        }
      }
    }
  }

  // Import dialog
  if (showImportDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      ModelImportDialog(uri = uri, onDismiss = { showImportDialog = false }, onDone = { info ->
        selectedImportedModelInfo.value = info
        showImportDialog = false
        showImportingDialog = true
      })
    }
  }

  // Importing in progress dialog.
  if (showImportingDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      selectedImportedModelInfo.value?.let { info ->
        ModelImportingDialog(uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            modelManagerViewModel.addImportedLlmModel(
              info = it,
            )
            showImportingDialog = false

            // Show a snack bar for successful import.
            scope.launch {
              snackbarHostState.showSnackbar("Model imported successfully")
            }
          })
      }
    }
  }

  // Alert dialog for unsupported file type.
  if (showUnsupportedFileTypeDialog) {
    AlertDialog(
      onDismissRequest = { showUnsupportedFileTypeDialog = false },
      title = { Text("Unsupported file type") },
      text = {
        Text("Only \".task\" file type is supported.")
      },
      confirmButton = {
        Button(onClick = { showUnsupportedFileTypeDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  if (uiState.loadingModelAllowlistError.isNotEmpty()) {
    AlertDialog(
      icon = {
        Icon(Icons.Rounded.Error, contentDescription = "", tint = MaterialTheme.colorScheme.error)
      },
      title = {
        Text(uiState.loadingModelAllowlistError)
      },
      text = {
        Text("Please check your internet connection and try again later.")
      },
      onDismissRequest = {
        modelManagerViewModel.loadModelAllowlist()
      },
      confirmButton = {
        TextButton(onClick = {
          modelManagerViewModel.loadModelAllowlist()
        }) {
          Text("Retry")
        }
      },
    )
  }
}

// Helper function to get the file name from a URI
fun getFileName(context: Context, uri: Uri): String? {
  if (uri.scheme == "content") {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1) {
          return cursor.getString(nameIndex)
        }
      }
    }
  } else if (uri.scheme == "file") {
    return uri.lastPathSegment
  }
  return null
}

@Preview
@Composable
fun HomeScreenPreview(
) {
  GalleryTheme {
    HomeScreen(
      modelManagerViewModel = PreviewModelManagerViewModel(context = LocalContext.current),
      navigateToTaskScreen = {},
    )
  }
}

