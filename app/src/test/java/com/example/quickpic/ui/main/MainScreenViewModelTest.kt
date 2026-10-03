package com.example.quickpic.ui.main

import com.example.quickpic.data.DataRepository
import com.example.quickpic.data.MediaLibrary
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun dataRepository_emitsItsMediaLibrary() = runTest {
    val expected = MediaLibrary(emptyList(), emptyList())
    assertEquals(expected, FakeMyModelRepository(expected).data.first())
  }

  @Test
  fun dataRepository_preservesTheLibraryContents() = runTest {
    val expected = MediaLibrary(emptyList(), emptyList())
    assertEquals(expected, FakeMyModelRepository(expected).data.first())
  }
}

private class FakeMyModelRepository(library: MediaLibrary) : DataRepository {
  override val data: Flow<MediaLibrary> = flow { emit(library) }
}
