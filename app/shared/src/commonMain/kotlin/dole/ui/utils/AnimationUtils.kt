package dole.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.keyframes

suspend fun Animatable<Float, AnimationVector1D>.triggerShakeAnimation() {
    this.animateTo(0f, keyframes {
        durationMillis = 400
        0f at 0
        (-20f) at 50
        20f at 100
        (-20f) at 150
        20f at 200
        0f at 400
    })
}