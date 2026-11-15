package dev.gfire.termvr.core.vt

import dev.gfire.termvr.core.model.FontConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the native OpenGL ES 3.0 renderer, glyph atlas, and 5-tier font shaper on the GL thread.
 */
class NativeGlRenderer(
    backendFactory: (() -> GlRendererBackend)? = null,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val backend: GlRendererBackend = when {
        backendFactory != null -> backendFactory()
        JniVtBindings.isNativeAvailable() -> JniGlRendererBackend()
        else -> JvmFallbackGlRendererBackend()
    }

    @Volatile
    var contextEpoch: Long = 1L
        private set

    fun setShaders(shaders: RendererShaderPack) {
        if (closed.get()) return
        backend.setShaders(shaders)
    }

    fun initGl(): Boolean = !closed.get() && backend.initGl()

    fun releaseGl() {
        if (!closed.get()) {
            backend.releaseGl()
        }
    }

    fun notifyContextLost(): Long {
        if (closed.get()) return contextEpoch
        val nextEpoch = contextEpoch + 1L
        contextEpoch = nextEpoch
        backend.notifyContextLost(nextEpoch)
        return nextEpoch
    }

    fun setBundledFonts(pack: BundledFontPack) {
        if (!closed.get()) {
            backend.setBundledFonts(pack)
        }
    }

    fun configureFont(config: FontConfig, density: Float): CellMetrics {
        if (closed.get()) return CellMetrics(10, 20, 16)
        return backend.configureFont(config, density)
    }

    fun detachSession(sessionHandle: Long) {
        if (!closed.get() && sessionHandle != 0L) {
            backend.detachSession(sessionHandle)
        }
    }

    fun detachSession(session: NativeVtSession) {
        detachSession(session.nativeHandle)
    }

    fun drawFrame(
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        panes: List<PaneRenderTarget>,
        dividers: List<SplitDividerRect> = emptyList(),
        forceFullRedraw: Boolean = false,
        surfaceClearRgba: UInt = 0x101418FFu,
    ): Boolean {
        if (closed.get() || surfaceWidthPx <= 0 || surfaceHeightPx <= 0) return false
        return backend.drawFrame(
            surfaceWidthPx = surfaceWidthPx,
            surfaceHeightPx = surfaceHeightPx,
            forceFullRedraw = forceFullRedraw,
            surfaceClearRgba = surfaceClearRgba,
            panes = panes,
            dividers = dividers,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backend.releaseGl()
        backend.close()
    }
}
