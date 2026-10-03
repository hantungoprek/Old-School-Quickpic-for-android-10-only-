package com.example.quickpic.ui.main

import androidx.activity.ComponentActivity
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.quickpic.data.MediaItem
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.quickpic.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent { MediaGrid(FAKE_DATA, onMediaClick = {}) }
  }

  @Test
  fun firstItem_exists() {
    FAKE_DATA.forEach { composeTestRule.onNodeWithText(it.displayName).assertExists() }
  }
}

private val FAKE_DATA = listOf(
  MediaItem(1, Uri.parse("content://media/1"), "Sample1", "image/jpeg", 0, 0, 0, "Pictures/"),
  MediaItem(2, Uri.parse("content://media/2"), "Sample2", "video/mp4", 0, 0, 0, "Movies/"),
)
