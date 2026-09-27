package dev.zakadi.sdk.capture

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * The lifecycle the pipeline binds the camera to: resumed while the pipeline runs, destroyed when
 * it stops, whatever the host's screens do. Main thread only.
 */
internal class PipelineLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var resumed = false

    override fun getLifecycle(): Lifecycle = registry

    fun resume() {
        if (resumed) return
        resumed = true
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (!resumed) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
