package tech.asahiart.luvia.ui

import android.animation.ValueAnimator
import android.provider.Settings
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.decisionQuestionText
import tech.asahiart.luvia.thread.sessionAgeLabel

/**
 * HANDOFF §1 numbers for the home decision stack. Pure so the flip rules can be
 * tested without a device. Layer 0 is the card directly behind the front card.
 */
internal object DecisionDeckMath {
    const val MAX_PEEK: Int = 2
    const val CORNER_DP: Float = 28f
    const val HORIZONTAL_INSET_DP: Float = 16f
    const val MAX_ROTATION_DEG: Float = 8f
    const val FLIP_VELOCITY_DP_PER_S: Float = 800f
    const val SINGLE_DRAG_SCALE: Float = 0.3f
    const val EDGE_DP: Float = 20f
    const val SLOP_DP: Float = 8f
    const val FLY_MS: Int = 220
    const val SPRING_RESPONSE_S: Float = 0.35f
    const val SPRING_DAMPING: Float = 0.85f
    const val COLLAPSE_MS: Int = 300
    const val ANSWER_MS: Int = 200
    const val ANSWER_SCALE: Float = 0.9f
    const val CROSSFADE_MS: Int = 150
    const val ARRIVE_MS: Int = 250
    const val ARRIVE_OFFSET_DP: Float = 12f
    const val PROMOTE_FROM_SCALE: Float = 0.96f

    /** Below this, a distance-triggered fly follows the drag instead of the velocity. */
    const val FLY_SPEED_FLOOR_PX_PER_S: Float = 50f

    val springStiffness: Float
        get() {
            val omega = (2.0 * PI / SPRING_RESPONSE_S).toFloat()
            return omega * omega
        }

    fun peekCount(index: Int, count: Int): Int {
        if (count <= 1 || index !in 0 until count) return 0
        val behind = count - index - 1
        if (behind <= 0) return 0
        return minOf(MAX_PEEK, behind)
    }

    fun peekOffsetDp(layer: Int): Float = if (layer <= 0) 6f else 12f

    fun peekScale(layer: Int): Float = if (layer <= 0) 0.96f else 0.92f

    fun peekAlpha(layer: Int): Float = if (layer <= 0) 0.72f else 0.45f

    fun counterLabel(index: Int, count: Int): String? {
        if (count <= 1 || index !in 0 until count) return null
        return "${index + 1}/$count"
    }

    fun nextIndex(index: Int, count: Int): Int = if (count <= 0) 0 else (index + 1) % count

    fun previousIndex(index: Int, count: Int): Int {
        if (count <= 0) return 0
        return (index - 1 + count) % count
    }

    /** After the front card is answered, i stays and clamps to the new n. */
    fun indexAfterAnswer(index: Int, countAfter: Int): Int {
        if (countAfter <= 0) return 0
        return index.coerceAtMost(countAfter - 1)
    }

    fun rotationDegrees(dxPx: Float, cardWidthPx: Float, allowRotation: Boolean): Float {
        if (!allowRotation || cardWidthPx <= 0f) return 0f
        val fraction = (dxPx / cardWidthPx).coerceIn(-1f, 1f)
        return MAX_ROTATION_DEG * fraction
    }

    fun visualDelta(dx: Float, dy: Float, singleCard: Boolean): Pair<Float, Float> {
        val scale = if (singleCard) SINGLE_DRAG_SCALE else 1f
        return dx * scale to dy * scale
    }

    /**
     * Flip when the larger axis exceeds one third of that axis, or speed exceeds
     * 800 dp/s. A single card never flips. Equal axes use the width.
     */
    fun shouldFlip(
        dxPx: Float,
        dyPx: Float,
        cardWidthPx: Float,
        cardHeightPx: Float,
        velocityXPxPerS: Float,
        velocityYPxPerS: Float,
        pxPerDp: Float,
        cardCount: Int,
    ): Boolean {
        if (cardCount <= 1) return false
        val density = pxPerDp.coerceAtLeast(0.01f)
        val speedDp = hypot(velocityXPxPerS, velocityYPxPerS) / density
        if (speedDp > FLIP_VELOCITY_DP_PER_S) return true
        val absX = abs(dxPx)
        val absY = abs(dyPx)
        return if (absX >= absY) {
            cardWidthPx > 0f && absX > cardWidthPx / 3f
        } else {
            cardHeightPx > 0f && absY > cardHeightPx / 3f
        }
    }

    fun flyVector(
        dxPx: Float,
        dyPx: Float,
        velocityXPxPerS: Float,
        velocityYPxPerS: Float,
        cardWidthPx: Float,
        cardHeightPx: Float,
    ): Pair<Float, Float> {
        val speed = hypot(velocityXPxPerS, velocityYPxPerS)
        val dirX = if (speed > FLY_SPEED_FLOOR_PX_PER_S) velocityXPxPerS else dxPx
        val dirY = if (speed > FLY_SPEED_FLOOR_PX_PER_S) velocityYPxPerS else dyPx
        val len = hypot(dirX, dirY).coerceAtLeast(1f)
        val distance = hypot(cardWidthPx, cardHeightPx).coerceAtLeast(1f) * 1.4f
        return dirX / len * distance to dirY / len * distance
    }

    /** False inside the left or right screen edge. The page must not exclude system gestures. */
    fun claimsGesture(windowXPx: Float, screenWidthPx: Float, edgePx: Float): Boolean {
        if (screenWidthPx <= 0f) return true
        return windowXPx >= edgePx && windowXPx <= screenWidthPx - edgePx
    }
}

internal fun reduceMotionEnabled(view: View): Boolean {
    if (!ValueAnimator.areAnimatorsEnabled()) return true
    val scale = Settings.Global.getFloat(
        view.context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return scale == 0f
}

private fun deckKey(thread: AgentThread): String = "${thread.hostId}:${thread.id}"

private fun <T> deckSpring() = spring<T>(
    dampingRatio = DecisionDeckMath.SPRING_DAMPING,
    stiffness = DecisionDeckMath.springStiffness,
)

@Composable
internal fun DecisionDeck(
    pending: List<AgentThread>,
    nowEpochMs: Long,
    buttonsEnabled: (AgentThread) -> Boolean,
    lockLabel: (AgentThread) -> String? = { null },
    dragEnabled: Boolean,
    onAnswer: (AgentThread, AskOption) -> Unit,
    onOpen: (AgentThread) -> Unit,
    onOpenTerminal: (AgentThread) -> Unit,
) {
    if (pending.isEmpty()) return
    val view = LocalView.current
    val reduceMotion = remember(view) { reduceMotionEnabled(view) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val edgePx = with(density) { DecisionDeckMath.EDGE_DP.dp.toPx() }
    val slopPx = with(density) { DecisionDeckMath.SLOP_DP.dp.toPx() }
    val pxPerDp = with(density) { 1.dp.toPx() }
    var index by remember { mutableIntStateOf(0) }
    var anchorId by remember { mutableStateOf<String?>(null) }
    var frozen by remember { mutableStateOf<List<AgentThread>?>(null) }
    var pastSlop by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var cardWidthPx by remember { mutableStateOf(0f) }
    var cardHeightPx by remember { mutableStateOf(0f) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }
    var flying by remember { mutableStateOf(false) }
    val displayed = frozen ?: pending
    if (displayed.isEmpty()) return
    val safeIndex = index.coerceIn(0, displayed.lastIndex)
    val front = displayed[safeIndex]
    val peek = DecisionDeckMath.peekCount(safeIndex, displayed.size)
    val counter = DecisionDeckMath.counterLabel(safeIndex, displayed.size)
    val shownOffset = if (dragging) drag else offset.value
    val rotation = DecisionDeckMath.rotationDegrees(
        shownOffset.x,
        cardWidthPx,
        allowRotation = !reduceMotion && displayed.size > 1,
    )

    LaunchedEffect(pending.map(::deckKey), flying) {
        if (flying || frozen != null) return@LaunchedEffect
        if (pending.isEmpty()) {
            index = 0
            anchorId = null
            return@LaunchedEffect
        }
        val anchor = anchorId
        val found = if (anchor == null) -1 else pending.indexOfFirst { deckKey(it) == anchor }
        index = if (found >= 0) {
            found
        } else {
            DecisionDeckMath.indexAfterAnswer(index, pending.size)
        }
        anchorId = deckKey(pending[index.coerceIn(0, pending.lastIndex)])
    }

    var seenPeek by remember { mutableStateOf(setOf<String>()) }
    var seenReady by remember { mutableStateOf(false) }
    val peekIds = (0 until peek).map { layer -> deckKey(displayed[safeIndex + layer + 1]) }.toSet()
    val freshPeek = if (seenReady) peekIds - seenPeek else emptySet()
    SideEffect {
        seenPeek = peekIds
        seenReady = true
    }

    fun releaseTo(target: Offset, fade: Boolean, duration: Int, then: () -> Unit) {
        val from = drag
        scope.launch {
            offset.snapTo(from)
            dragging = false
            drag = Offset.Zero
            if (fade && reduceMotion) {
                alpha.animateTo(0f, tween(DecisionDeckMath.CROSSFADE_MS))
            } else if (fade) {
                coroutineScope {
                    launch { alpha.animateTo(0f, tween(duration, easing = EaseOut)) }
                    launch { offset.animateTo(target, tween(duration, easing = EaseOut)) }
                }
            } else {
                offset.animateTo(target, deckSpring())
            }
            then()
        }
    }

    fun settleFront(next: Int, promote: Boolean) {
        scope.launch {
            offset.snapTo(Offset.Zero)
            alpha.snapTo(1f)
            drag = Offset.Zero
            dragging = false
            val list = frozen ?: pending
            if (list.isNotEmpty()) {
                val clamped = next.coerceIn(0, list.lastIndex)
                anchorId = deckKey(list[clamped])
                index = clamped
            }
            if (promote && !reduceMotion && list.isNotEmpty()) {
                scale.snapTo(DecisionDeckMath.PROMOTE_FROM_SCALE)
                scale.animateTo(1f, deckSpring())
            } else {
                scale.snapTo(1f)
            }
            frozen = null
            flying = false
            pastSlop = false
        }
    }

    fun finishDrag(total: Offset, velocity: Offset) {
        val list = frozen ?: displayed
        val flip = DecisionDeckMath.shouldFlip(
            total.x,
            total.y,
            cardWidthPx,
            cardHeightPx,
            velocity.x,
            velocity.y,
            pxPerDp,
            list.size,
        )
        if (!flip) {
            flying = false
            releaseTo(Offset.Zero, fade = false, duration = 0) {
                pastSlop = false
                frozen = null
                flying = false
            }
            return
        }
        flying = true
        val (tx, ty) = DecisionDeckMath.flyVector(
            total.x,
            total.y,
            velocity.x,
            velocity.y,
            cardWidthPx,
            cardHeightPx,
        )
        releaseTo(
            target = Offset(tx, ty),
            fade = true,
            duration = if (reduceMotion) DecisionDeckMath.CROSSFADE_MS else DecisionDeckMath.FLY_MS,
        ) {
            settleFront(DecisionDeckMath.nextIndex(safeIndex, list.size), promote = !reduceMotion)
        }
    }

    fun answer(thread: AgentThread, option: AskOption) {
        if (pastSlop || flying) return
        flying = true
        scope.launch {
            if (!reduceMotion) {
                coroutineScope {
                    launch { alpha.animateTo(0f, tween(DecisionDeckMath.ANSWER_MS, easing = EaseOut)) }
                    launch {
                        scale.animateTo(
                            DecisionDeckMath.ANSWER_SCALE,
                            tween(DecisionDeckMath.ANSWER_MS, easing = EaseOut),
                        )
                    }
                }
            }
            onAnswer(thread, option)
            alpha.snapTo(1f)
            scale.snapTo(1f)
            flying = false
        }
    }

    val agent = front.agentType?.takeIf { it.isNotBlank() } ?: front.title
    val question = decisionQuestionText(front).orEmpty()
    val spoken = "$agent 等你决定：$question，第 ${safeIndex + 1} 个，共 ${displayed.size} 个"
    Box(Modifier.fillMaxWidth().padding(bottom = if (peek > 0) 12.dp else 0.dp)) {
        for (layer in (peek - 1) downTo 0) {
            val behind = displayed[safeIndex + layer + 1]
            key(deckKey(behind)) {
            PeekLayer(
                fresh = deckKey(behind) in freshPeek,
                layer = layer,
                reduceMotion = reduceMotion,
            ) {
                DecisionPreview(
                    thread = behind,
                    age = sessionAgeLabel(behind.updatedEpochMs, nowEpochMs),
                    enabled = false,
                    counter = null,
                    onAnswer = {},
                    onOpen = {},
                    onOpenTerminal = {},
                )
            }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DecisionDeckMath.HORIZONTAL_INSET_DP.dp)
                .onGloballyPositioned {
                    coordinates[0] = it
                    cardWidthPx = it.size.width.toFloat()
                    cardHeightPx = it.size.height.toFloat()
                }
                .graphicsLayer {
                    translationX = shownOffset.x
                    translationY = shownOffset.y
                    rotationZ = rotation
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                    transformOrigin = TransformOrigin.Center
                }
                .semantics {
                    contentDescription = lockLabel(front)?.let { "$spoken。$it" } ?: spoken
                    liveRegion = LiveRegionMode.Polite
                    if (displayed.size > 1 && dragEnabled) {
                        customActions = listOf(
                            CustomAccessibilityAction("下一张") {
                                frozen = pending
                                settleFront(
                                    DecisionDeckMath.nextIndex(safeIndex, displayed.size),
                                    promote = !reduceMotion,
                                )
                                true
                            },
                            CustomAccessibilityAction("上一张") {
                                frozen = pending
                                settleFront(
                                    DecisionDeckMath.previousIndex(safeIndex, displayed.size),
                                    promote = !reduceMotion,
                                )
                                true
                            },
                        )
                    }
                }
                .deckDrag(
                    enabled = dragEnabled && !flying,
                    slopPx = slopPx,
                    edgePx = edgePx,
                    screenWidthPx = screenWidthPx,
                    coordinates = { coordinates[0] },
                    onDragStart = { total ->
                        frozen = pending
                        dragging = true
                        pastSlop = true
                        val (dx, dy) = DecisionDeckMath.visualDelta(total.x, total.y, displayed.size <= 1)
                        drag = Offset(dx, dy)
                    },
                    onDrag = { delta ->
                        val (dx, dy) = DecisionDeckMath.visualDelta(delta.x, delta.y, displayed.size <= 1)
                        drag += Offset(dx, dy)
                    },
                    onEnd = { total, velocity -> finishDrag(total, velocity) },
                    onCancel = {
                        pastSlop = false
                        releaseTo(Offset.Zero, fade = false, duration = 0) {
                            frozen = null
                        }
                    },
                ),
        ) {
            DecisionPreview(
                thread = front,
                age = sessionAgeLabel(front.updatedEpochMs, nowEpochMs),
                enabled = buttonsEnabled(front) && !pastSlop && !flying,
                counter = counter,
                onAnswer = { option -> answer(front, option) },
                onOpen = { if (!pastSlop && !flying) onOpen(front) },
                onOpenTerminal = { if (!pastSlop && !flying) onOpenTerminal(front) },
            )
        }
    }
}

@Composable
private fun PeekLayer(
    fresh: Boolean,
    layer: Int,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (fresh && !reduceMotion) 0f else 1f) }
    LaunchedEffect(fresh) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(DecisionDeckMath.ARRIVE_MS, easing = EaseOut))
        }
    }
    val extra = (1f - progress.value) * DecisionDeckMath.ARRIVE_OFFSET_DP
    val y = DecisionDeckMath.peekOffsetDp(layer) + extra
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DecisionDeckMath.HORIZONTAL_INSET_DP.dp)
            .clearAndSetSemantics { }
            .graphicsLayer {
                val peekScale = DecisionDeckMath.peekScale(layer)
                scaleX = peekScale
                scaleY = peekScale
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = y.dp.toPx()
                alpha = DecisionDeckMath.peekAlpha(layer) * progress.value
            },
    ) {
        content()
    }
}

private fun Modifier.deckDrag(
    enabled: Boolean,
    slopPx: Float,
    edgePx: Float,
    screenWidthPx: Float,
    coordinates: () -> LayoutCoordinates?,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: (total: Offset, velocity: Offset) -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(enabled, slopPx, edgePx, screenWidthPx) {
    if (!enabled) return@pointerInput
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val windowX = coordinates()?.localToWindow(down.position)?.x ?: down.position.x
        if (!DecisionDeckMath.claimsGesture(windowX, screenWidthPx, edgePx)) {
            return@awaitEachGesture
        }
        tracker.resetTracking()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var past = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                if (past) onCancel()
                break
            }
            if (change.changedToUp() || !change.pressed) {
                if (past) {
                    val velocity = tracker.calculateVelocity()
                    onEnd(total, Offset(velocity.x, velocity.y))
                }
                break
            }
            val delta = change.positionChange()
            total += delta
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!past && total.getDistance() >= slopPx) {
                past = true
                onDragStart(total)
            } else if (past) {
                change.consume()
                onDrag(delta)
            }
            if (past) change.consume()
        }
    }
}
