package com.roundsalmon4.monochrome.ui.common

import androidx.lifecycle.ViewModel
import com.roundsalmon4.monochrome.core.api.Availability
import com.roundsalmon4.monochrome.core.api.TrackAvailabilityChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Shares the [TrackAvailabilityChecker]'s album aggregate status with listing
 * screens (Home, Artist, Search) so album cards can show a green/yellow/red dot.
 */
@HiltViewModel
class AlbumAvailabilityViewModel @Inject constructor(
    checker: TrackAvailabilityChecker
) : ViewModel() {
    val albumStatus: StateFlow<Map<String, Availability>> = checker.albumStatus
}