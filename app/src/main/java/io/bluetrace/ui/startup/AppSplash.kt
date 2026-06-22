package io.bluetrace.ui.startup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.ui.theme.BT

/**
 * 应用内启动屏（1:1 贴原型「启动A」）：浅底 + 渐变圆角 logo（内含白色脉冲线）+ 渐变字标 BlueTrace
 * + 副标 + 三点加载动画。系统 SplashScreen（一闪而过、只画图标+底色）之后由这一帧接管，
 * 承载系统 splash 画不了的文字/动画（[io.bluetrace.ui.BlueTraceApp] 冷启动展示一次，§5.1）。
 *
 * 视觉对应原型 .splash-logo(86/r24/135°渐变/阴影)、.splash-name(25/800 渐变字)、
 * .splash-tag(12/onSurfaceV)、.dots(3×7 primary blink)。色值用 [BT] tokens 同源。
 */
@Composable
fun AppSplash() {
    // 底色 / 副标色用主题感知 token（BT.bg / BT.onSurfaceV）→ 跟随全局外观模式；且与系统 SplashScreen
    // 同底（深色 splash_bg = values-night #11151C = DarkScheme.bg）→ 系统层与应用内无缝、只见一个开屏。
    val logoGrad = Brush.linearGradient(
        colors = listOf(BT.primary, BT.tertiary),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY), // ≈135° 左上→右下
    )
    Box(Modifier.fillMaxSize().background(BT.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(86.dp)
                    .shadow(14.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(logoGrad),
                contentAlignment = Alignment.Center,
            ) {
                PulseMark(Modifier.size(46.dp))
            }
            Spacer(Modifier.height(15.dp))
            Text(
                text = "BlueTrace",
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(BT.primary, BT.tertiary)),
                    fontSize = 25.sp,
                    fontWeight = FontWeight.W800,
                    letterSpacing = (-0.3).sp,
                ),
            )
            Spacer(Modifier.height(7.dp))
            Text(text = stringResource(R.string.splash_tagline), color = BT.onSurfaceV, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            LoadingDots()
        }
    }
}

/** 白色脉冲/心跳折线（原型同款 path，24 单位坐标系按画布等比缩放）。 */
@Composable
private fun PulseMark(modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val path = Path().apply {
            moveTo(2.5f * u, 12.5f * u)
            lineTo(6.5f * u, 12.5f * u)
            lineTo(8.5f * u, 6.9f * u)
            lineTo(12f * u, 17.9f * u)
            lineTo(14.2f * u, 12.5f * u)
            lineTo(21.5f * u, 12.5f * u)
        }
        drawPath(
            path,
            color = Color.White,
            style = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 三点加载动画（原型 .dots：1.2s blink，相邻延迟 0.2s）。 */
@Composable
private fun LoadingDots() {
    val t = rememberInfiniteTransition(label = "splashDots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val alpha by t.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).clip(CircleShape).background(BT.primary.copy(alpha = alpha)))
        }
    }
}
