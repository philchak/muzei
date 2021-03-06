/*
 * Copyright 2014 Google Inc.
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

package com.google.android.apps.muzei

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.content.res.ResourcesCompat
import android.support.v7.widget.ActionMenuView
import android.support.v7.widget.TooltipCompat
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.notifications.NewWallpaperNotificationReceiver
import com.google.android.apps.muzei.render.ArtworkSizeLiveData
import com.google.android.apps.muzei.render.SwitchingPhotosDone
import com.google.android.apps.muzei.render.SwitchingPhotosInProgress
import com.google.android.apps.muzei.render.SwitchingPhotosLiveData
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.room.sendAction
import com.google.android.apps.muzei.settings.AboutActivity
import com.google.android.apps.muzei.sources.SourceArtProvider
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.AnimatedMuzeiLoadingSpinnerView
import com.google.android.apps.muzei.util.PanScaleProxyView
import com.google.android.apps.muzei.util.makeCubicGradientScrimDrawable
import com.google.android.apps.muzei.util.observeNonNull
import com.google.android.apps.muzei.widget.showWidgetPreview
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.nurik.roman.muzei.R

object ArtDetailOpenLiveData : MutableLiveData<Boolean>()

class ArtDetailFragment : Fragment(), (Boolean) -> Unit {

    companion object {
        private val SOURCE_ACTION_IDS = intArrayOf(
                R.id.source_action_1,
                R.id.source_action_2,
                R.id.source_action_3,
                R.id.source_action_4,
                R.id.source_action_5,
                R.id.source_action_6,
                R.id.source_action_7,
                R.id.source_action_8,
                R.id.source_action_9,
                R.id.source_action_10)
    }

    private var currentViewportId = 0
    private var wallpaperAspectRatio: Float = 0f
    private var artworkAspectRatio: Float = 0f

    private val providerObserver = Observer<Provider?> { provider ->
        val supportsNextArtwork = provider?.supportsNextArtwork == true
        nextButton.isVisible = supportsNextArtwork
    }

    private val artworkObserver = Observer<Artwork?> { currentArtwork ->
        if (currentArtwork == null) {
            return@Observer
        }
        var titleFont = R.font.alegreya_sans_black
        var bylineFont = R.font.alegreya_sans_medium
        if (MuzeiContract.Artwork.META_FONT_TYPE_ELEGANT == currentArtwork.metaFont) {
            titleFont = R.font.alegreya_black_italic
            bylineFont = R.font.alegreya_italic
        }

        titleView.typeface = ResourcesCompat.getFont(requireContext(), titleFont)
        titleView.text = currentArtwork.title

        bylineView.typeface = ResourcesCompat.getFont(requireContext(), bylineFont)
        bylineView.text = currentArtwork.byline

        val attribution = currentArtwork.attribution
        if (attribution?.isNotEmpty() == true) {
            attributionView.text = attribution
            attributionView.isVisible = true
        } else {
            attributionView.isGone = true
        }

        metadataView.setOnClickListener {
            currentArtworkLiveData.value?.openArtworkInfo(requireContext())
        }

        launch(UI) {
            val commands = async{
                currentArtwork.getCommands(requireContext())
            }.await()
            overflowSourceActionMap.clear()
            overflowMenu.menu.clear()
            requireActivity().menuInflater.inflate(R.menu.muzei_overflow,
                    overflowMenu.menu)
            commands.take(SOURCE_ACTION_IDS.size).forEachIndexed { i, action ->
                overflowSourceActionMap.put(SOURCE_ACTION_IDS[i], action.id)
                val menuItem = overflowMenu.menu.add(0, SOURCE_ACTION_IDS[i],
                        0, action.title)
                if (action.id == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK &&
                        currentProviderLiveData.value?.componentName?.equals(
                                ComponentName(requireActivity(), SourceArtProvider::class.java)
                        ) == true) {
                    menuItem.setIcon(R.drawable.ic_skip)
                    menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
        }
        showFakeLoading = false
        updateLoadingSpinnerVisibility()
    }

    private var guardViewportChangeListener: Boolean = false
    private var deferResetViewport: Boolean = false

    private lateinit var containerView: View
    private lateinit var overflowMenu: ActionMenuView
    private val overflowSourceActionMap = SparseIntArray()
    private lateinit var chromeContainerView: View
    private lateinit var metadataView: View
    private lateinit var loadingContainerView: View
    private lateinit var loadingIndicatorView: AnimatedMuzeiLoadingSpinnerView
    private lateinit var nextButton: View
    private lateinit var titleView: TextView
    private lateinit var bylineView: TextView
    private lateinit var attributionView: TextView
    private lateinit var panScaleProxyView: PanScaleProxyView
    private var loadingSpinnerShown = false
    private var showFakeLoading = false
    private val currentProviderLiveData: LiveData<Provider?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).providerDao().currentProvider
    }
    private val currentArtworkLiveData: LiveData<Artwork?> by lazy {
        MuzeiDatabase.getInstance(requireContext()).artworkDao().currentArtwork
    }

    private var unsetNextFakeLoading: Job? = null
    private var showLoadingSpinner: Job? = null

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        containerView = inflater.inflate(R.layout.art_detail_fragment, container, false)

        chromeContainerView = containerView.findViewById(R.id.chrome_container)
        showHideChrome(true)

        return containerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        @Suppress("DEPRECATION")
        view.requestFitSystemWindows()

        chromeContainerView.background = makeCubicGradientScrimDrawable(Gravity.BOTTOM, 0xAA)

        metadataView = view.findViewById(R.id.metadata)

        val metadataSlideDistance = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
        containerView.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0

            chromeContainerView.isVisible = true
            chromeContainerView.animate()
                    .alpha(if (visible) 1f else 0f)
                    .translationY(if (visible) 0f else metadataSlideDistance)
                    .setDuration(200)
                    .withEndAction {
                        if (!visible) {
                            chromeContainerView.isGone = true
                        }
                    }
        }

        titleView = view.findViewById(R.id.title)
        bylineView = view.findViewById(R.id.byline)
        attributionView = view.findViewById(R.id.attribution)

        overflowMenu = view.findViewById(R.id.overflow_menu_view)
        overflowMenu.overflowIcon = ContextCompat.getDrawable(requireContext(),
                R.drawable.ic_overflow)
        overflowMenu.setOnMenuItemClickListener { menuItem ->
            val context = context ?: return@setOnMenuItemClickListener false
            val id = overflowSourceActionMap.get(menuItem.itemId)
            if (id > 0) {
                currentArtworkLiveData.value?.run {
                    launch {
                        sendAction(requireContext(), id)
                    }
                }
                return@setOnMenuItemClickListener true
            }

            return@setOnMenuItemClickListener when (menuItem.itemId) {
                R.id.action_about -> {
                    FirebaseAnalytics.getInstance(context).logEvent("about_open", null)
                    startActivity(Intent(context, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        nextButton = view.findViewById(R.id.next_button)
        nextButton.setOnClickListener {
            ProviderManager.getInstance(requireContext()).nextArtwork()
            showFakeLoading()
        }
        TooltipCompat.setTooltipText(nextButton, nextButton.contentDescription)

        panScaleProxyView = view.findViewById(R.id.pan_scale_proxy)
        panScaleProxyView.apply {
            setMaxZoom(5)
            onViewportChanged = {
                if (!guardViewportChangeListener) {
                    ArtDetailViewport.setViewport(
                            currentViewportId, panScaleProxyView.currentViewport, true)
                }
            }
            onSingleTapUp = {
                val window = activity?.window
                if (window != null) {
                    showHideChrome(window.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                onLongPress = {
                    showWidgetPreview(requireContext().applicationContext)
                }
            }
        }

        loadingContainerView = view.findViewById(R.id.image_loading_container)
        loadingIndicatorView = view.findViewById(R.id.image_loading_indicator)

        WallpaperSizeLiveData.observeNonNull(this) { size ->
            wallpaperAspectRatio = if (size.height > 0) {
                size.width * 1f / size.height
            } else {
                panScaleProxyView.width * 1f / panScaleProxyView.height
            }
            resetProxyViewport()
        }

        ArtworkSizeLiveData.observeNonNull(this) { size ->
            artworkAspectRatio = size.width * 1f / size.height
            resetProxyViewport()
        }

        ArtDetailViewport.addObserver(this)

        SwitchingPhotosLiveData.observeNonNull(this) { switchingPhotos ->
            currentViewportId = switchingPhotos.viewportId
            panScaleProxyView.panScaleEnabled = switchingPhotos is SwitchingPhotosDone
            // Process deferred artwork size change when done switching
            if (switchingPhotos is SwitchingPhotosDone && deferResetViewport) {
                resetProxyViewport()
            }
        }

        currentProviderLiveData.observe(this, providerObserver)
        currentArtworkLiveData.observe(this, artworkObserver)
    }

    override fun onStart() {
        super.onStart()
        ArtDetailOpenLiveData.value = true
    }

    override fun onResume() {
        super.onResume()
        NewWallpaperNotificationReceiver.markNotificationRead(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unsetNextFakeLoading?.cancel()
        showLoadingSpinner?.cancel()
        ArtDetailViewport.removeObserver(this)
        currentProviderLiveData.removeObserver(providerObserver)
        currentArtworkLiveData.removeObserver(artworkObserver)
    }

    private fun showHideChrome(show: Boolean) {
        var flags = if (show) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
        flags = flags or (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        if (!show) {
            flags = flags or (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
        requireActivity().window.decorView.systemUiVisibility = flags
    }

    private fun resetProxyViewport() {
        if (wallpaperAspectRatio == 0f || artworkAspectRatio == 0f) {
            return
        }

        deferResetViewport = false
        if (SwitchingPhotosLiveData.value is SwitchingPhotosInProgress) {
            deferResetViewport = true
            return
        }

        panScaleProxyView.relativeAspectRatio = artworkAspectRatio / wallpaperAspectRatio
    }

    override fun invoke(isFromUser: Boolean) {
        if (!isFromUser) {
            guardViewportChangeListener = true
            panScaleProxyView.setViewport(ArtDetailViewport.getViewport(currentViewportId))
            guardViewportChangeListener = false
        }
    }

    override fun onStop() {
        super.onStop()
        overflowMenu.hideOverflowMenu()
        ArtDetailOpenLiveData.value = false
    }

    private fun showFakeLoading() {
        showFakeLoading = true
        // Show a loading spinner for up to 10 seconds. When new artwork is loaded,
        // the loading spinner will go away.
        updateLoadingSpinnerVisibility()
        unsetNextFakeLoading?.cancel()
        unsetNextFakeLoading = launch(UI) {
            delay(10000)
            showFakeLoading = false
            updateLoadingSpinnerVisibility()
        }
        updateLoadingSpinnerVisibility()
    }

    private fun updateLoadingSpinnerVisibility() {
        if (showFakeLoading != loadingSpinnerShown) {
            loadingSpinnerShown = showFakeLoading
            showLoadingSpinner?.cancel()?.also {
                showLoadingSpinner = null
            }
            if (showFakeLoading) {
                this.showLoadingSpinner = launch(UI) {
                    delay(700)
                    loadingIndicatorView.start()
                    loadingContainerView.isVisible = true
                    loadingContainerView.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .withEndAction(null)
                }
            } else {
                loadingContainerView.animate()
                        .alpha(0f)
                        .setDuration(1000)
                        .withEndAction {
                            loadingContainerView.isGone = true
                            loadingIndicatorView.stop()
                        }
            }
        }
    }
}
