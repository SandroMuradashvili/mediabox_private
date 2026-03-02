package ge.mediabox.mediabox.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated particle star-field + slow aurora blobs.
 *
 * Used as the background layer on both LoginActivity and MainActivity.
 * Renders two layers:
 *   1. ~120 small drifting star particles at varying depths (parallax speed)
 *   2. 3 large soft radial aurora blobs that drift slowly + pulse opacity
 *
 * Entirely canvas-drawn — zero bitmaps, zero external deps.
 * CPU cost is negligible (all primitive draws, ~1ms per frame on mid-range TV).
 */
class ParticleFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Stars ─────────────────────────────────────────────────────────────────

    private data class Star(
        var x: Float,
        var y: Float,
        val radius: Float,
        val speed: Float,       // pixels/frame drift downward
        val alpha: Float,       // base alpha 0..1
        val twinkleOffset: Float // phase offset for opacity oscillation
    )

    private val stars = mutableListOf<Star>()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var frameCount = 0

    // ── Aurora blobs ──────────────────────────────────────────────────────────

    private data class Blob(
        var cx: Float,
        var cy: Float,
        val radius: Float,
        val color: Int,
        val driftX: Float,
        val driftY: Float,
        val pulseSpeed: Float,
        val pulseOffset: Float
    )

    private val blobs = mutableListOf<Blob>()
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Init ──────────────────────────────────────────────────────────────────

    private val rng = Random(System.currentTimeMillis())

    private var initialized = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        initialized = false
        initParticles(w, h)
        initialized = true
        invalidate()
    }

    private fun initParticles(w: Int, h: Int) {
        stars.clear()
        repeat(110) {
            stars.add(Star(
                x = rng.nextFloat() * w,
                y = rng.nextFloat() * h,
                radius = rng.nextFloat() * 1.6f + 0.4f,
                speed = rng.nextFloat() * 0.12f + 0.04f,
                alpha = rng.nextFloat() * 0.55f + 0.1f,
                twinkleOffset = rng.nextFloat() * 360f
            ))
        }

        blobs.clear()
        // Three aurora blobs, different hues
        val blobDefs = listOf(
            Triple(0xFF1A1060.toInt(), 0.35f, 0.10f),   // deep indigo
            Triple(0xFF0D2B45.toInt(), 0.55f, 0.15f),   // teal-blue
            Triple(0xFF1A0830.toInt(), 0.45f, 0.12f)    // violet
        )
        blobDefs.forEachIndexed { i, (color, wx, wy) ->
            blobs.add(Blob(
                cx = w * (0.2f + i * 0.3f),
                cy = h * (0.25f + rng.nextFloat() * 0.5f),
                radius = (w * 0.38f + h * 0.22f),
                color = color,
                driftX = (rng.nextFloat() - 0.5f) * 0.4f,
                driftY = (rng.nextFloat() - 0.5f) * 0.2f,
                pulseSpeed = 0.008f + rng.nextFloat() * 0.006f,
                pulseOffset = rng.nextFloat() * Math.PI.toFloat() * 2f
            ))
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (!initialized || width <= 0 || height <= 0) return

        frameCount++
        val t = frameCount * 0.016f // ~time in seconds at 60fps

        // ── Aurora blobs ──────────────────────────────────────────────────────
        blobs.forEach { blob ->
            // Drift
            blob.cx += blob.driftX
            blob.cy += blob.driftY

            // Soft bounce at edges
            if (blob.cx < -blob.radius * 0.3f) blob.cx = width + blob.radius * 0.3f
            if (blob.cx > width + blob.radius * 0.3f) blob.cx = -blob.radius * 0.3f
            if (blob.cy < -blob.radius * 0.4f) blob.cy = height + blob.radius * 0.4f
            if (blob.cy > height + blob.radius * 0.4f) blob.cy = -blob.radius * 0.4f

            // Pulse opacity
            val pulse = (Math.sin((t * blob.pulseSpeed * 60 + blob.pulseOffset).toDouble()) * 0.5 + 0.5).toFloat()
            val blobAlpha = (0.08f + pulse * 0.07f).coerceIn(0f, 1f)

            // Radial gradient blob
            val gradient = RadialGradient(
                blob.cx, blob.cy, blob.radius,
                intArrayOf(applyAlpha(blob.color, blobAlpha), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            blobPaint.shader = gradient
            canvas.drawCircle(blob.cx, blob.cy, blob.radius, blobPaint)
        }

        // ── Stars ─────────────────────────────────────────────────────────────
        stars.forEach { star ->
            // Drift downward slowly
            star.y += star.speed
            if (star.y > height + 2f) {
                star.y = -2f
                star.x = rng.nextFloat() * width
            }

            // Twinkle: oscillate alpha
            val twinkle = (Math.sin((t * 60 * 0.012f + star.twinkleOffset * 0.0174f).toDouble()) * 0.3 + 0.7).toFloat()
            val finalAlpha = (star.alpha * twinkle).coerceIn(0f, 1f)

            starPaint.alpha = (finalAlpha * 255).toInt()
            canvas.drawCircle(star.x, star.y, star.radius, starPaint)
        }

        // Schedule next frame
        postInvalidateOnAnimation()
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}