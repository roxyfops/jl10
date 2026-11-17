package com.bb10.kit

import android.content.Context
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch

class KitComposeView(c: Context) : FrameLayout(c), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    val compose = ComposeView(c)

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        setViewTreeViewModelStoreOwner(this)
        setViewTreeOnBackPressedDispatcherOwner(this)
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setContent(content: @Composable () -> Unit) = compose.setContent(content)

    fun handleBack(): Boolean {
        if (!onBackPressedDispatcher.hasEnabledCallbacks()) return false
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private var recomposer: androidx.compose.runtime.Recomposer? = null

    override fun onAttachedToWindow() {
        if (recomposer == null) {
            val context = androidx.compose.ui.platform.AndroidUiDispatcher.CurrentThread
            val r = androidx.compose.runtime.Recomposer(context)
            kotlinx.coroutines.CoroutineScope(context).launch { r.runRecomposeAndApplyChanges() }
            compose.setParentCompositionContext(r)
            recomposer = r
        }
        super.onAttachedToWindow()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDetachedFromWindow() {
        registry.currentState = Lifecycle.State.CREATED
        super.onDetachedFromWindow()
        post {
            if (!isAttachedToWindow) {
                recomposer?.cancel()
                recomposer = null
            }
        }
    }
}
