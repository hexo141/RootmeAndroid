package com.hexo141.rootmeandroid

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// ============================================================================
// GLSL ES 1.00 着色器：从 AGSL 移植，通过 OpenGL ES 2.0 渲染
// 着色器1：火车场景（云层 + 火车 + 烟 + 桥）
// 着色器2：暗角效果（vignette）
// ============================================================================

// 全屏四边形顶点（TRIANGLE_STRIP）
private val VERTEX_SHADER = """
attribute vec4 aPosition;
void main() {
    gl_Position = aPosition;
}
""".trimIndent()

private val FRAGMENT_SHADER = """
precision highp float;
uniform vec2 iResolution;
uniform float iTime;
uniform vec3 iBgColor;
uniform sampler2D iChannel0;

// 纹理采样噪声，忠实还原 Shadertoy 原版
float noise(vec2 x) {
    vec2 f = fract(x);
    vec2 u = f*f*f*(f*(f*6.0-15.0)+10.0);
    vec2 p = floor(x);
    float a = texture2D(iChannel0, (p+vec2(0.0, 0.0))/1024.0).x;
    float b = texture2D(iChannel0, (p+vec2(1.0, 0.0))/1024.0).x;
    float c = texture2D(iChannel0, (p+vec2(0.0, 1.0))/1024.0).x;
    float d = texture2D(iChannel0, (p+vec2(1.0, 1.0))/1024.0).x;
    return a+(b-a)*u.x+(c-a)*u.y+(a-b-c+d)*u.x*u.y;
}

float fbm(vec2 x, int detail) {
    float a = 0.0;
    float b = 1.0;
    float t = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i < detail) {
            float n = noise(x);
            a += b * n;
            t += b;
            b *= 0.7;
            x *= 2.0;
        }
    }
    return a / t;
}

float fbm2(vec2 x, int detail) {
    float a = 0.0;
    float b = 1.0;
    float t = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i < detail) {
            float n = noise(x);
            a += b * n;
            t += b;
            b *= 0.9;
            x *= 2.0;
        }
    }
    return a / t;
}

float box(vec2 uv, float x1, float x2, float y1, float y2) {
    return (uv.x > x1 && uv.x < x2 && uv.y > y1 && uv.y < y2) ? 1.0 : 0.0;
}

#define dot2(v) dot(v, v)
#define layer(dh, v) if (uv.y < h + midlevel - (dh)) return vec4(v, 1.0);

vec4 foreground(vec2 uv, float t) {
    float midlevel;
    float h;
    vec2 uv2;

    uv.y -= 0.2;

    midlevel = -0.1;
    uv2 = uv + vec2(t / 1.0 + 40.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 1.7;
    layer(0.12, vec3(0.43, 0.32, 0.31));
    layer(0.08, vec3(0.55, 0.42, 0.41));
    layer(0.04, vec3(0.66, 0.42, 0.40));
    layer(0.0, vec3(0.77, 0.48, 0.46));

    midlevel = 0.05;
    uv2 = uv + vec2(t / 2.0 + 38.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 1.7;
    layer(0.1, vec3(0.95, 0.66, 0.48));
    layer(0.04, vec3(0.98, 0.76, 0.64));
    layer(0.0, vec3(0.95, 0.80, 0.77));

    return vec4(0.95, 0.80, 0.77, 0.0);
}

vec4 background(vec2 uv, float t) {
    float midlevel;
    float h;
    vec2 uv2;

    midlevel = 0.3;
    uv2 = uv + vec2(t / 10.0 + 32.5, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 0.9;
    layer(0.14, vec3(0.48, 0.19, 0.20));
    layer(0.1, vec3(0.68, 0.28, 0.19));
    layer(0.07, vec3(0.88, 0.38, 0.24));
    layer(0.0, vec3(0.95, 0.45, 0.30));

    midlevel = 0.35;
    uv2 = uv + vec2(t / 15.0 + 30.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 1.0;
    layer(0.04, vec3(0.98, 0.76, 0.64));
    layer(0.0, vec3(0.95, 0.80, 0.77));

    midlevel = 0.35;
    uv2 = uv + vec2(t / 20.0 + 27.5, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 3.5;
    layer(0.12, vec3(0.43, 0.32, 0.31));
    layer(0.08, vec3(0.55, 0.42, 0.41));
    layer(0.04, vec3(0.66, 0.42, 0.40));
    layer(0.0, vec3(0.77, 0.48, 0.46));

    midlevel = 0.45;
    uv2 = uv + vec2(t / 25.0 + 23.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 2.0;
    layer(0.04, vec3(0.98, 0.57, 0.36));
    layer(0.0, vec3(1.0, 0.62, 0.44));

    midlevel = 0.5;
    uv2 = uv + vec2(t / 30.0 + 20.5, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 2.3;
    layer(0.12, vec3(0.41, 0.27, 0.27));
    layer(0.08, vec3(0.53, 0.35, 0.32));
    layer(0.04, vec3(0.80, 0.24, 0.17));
    layer(0.0, vec3(0.99, 0.29, 0.20));

    midlevel = 0.5;
    uv2 = uv + vec2(t / 35.0 + 18.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 2.5;
    layer(0.1, vec3(0.88, 0.38, 0.24));
    layer(0.05, vec3(0.98, 0.42, 0.28));
    layer(0.0, vec3(1.0, 0.48, 0.35));

    midlevel = 0.6;
    uv2 = uv + vec2(t / 40.0 + 18.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 2.0;
    layer(0.1, vec3(0.95, 0.66, 0.48));
    layer(0.0, vec3(1.0, 0.76, 0.60));

    midlevel = 0.75;
    uv2 = uv + vec2(t / 45.0 + 15.5, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 3.5;
    layer(0.2, vec3(1.0, 0.55, 0.33));
    layer(0.15, vec3(0.98, 0.50, 0.24));
    layer(0.1, vec3(0.90, 0.55, 0.40));
    layer(0.0, vec3(1.0, 0.62, 0.44));

    midlevel = 0.7;
    uv2 = uv + vec2(t / 50.0 + 12.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 2.7;
    layer(0.04, vec3(0.73, 0.36, 0.30));
    layer(0.0, vec3(0.80, 0.40, 0.34));

    midlevel = 0.8;
    uv2 = uv + vec2(t / 60.0 + 9.5, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 2.7;
    layer(0.1, vec3(0.93, 0.58, 0.35));
    layer(0.0, vec3(1.0, 0.76, 0.60));

    midlevel = 0.9;
    uv2 = uv + vec2(t / 70.0 + 7.0, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 3.0;
    layer(0.1, vec3(0.56, 0.25, 0.22));
    layer(0.05, vec3(0.60, 0.30, 0.27));
    layer(0.0, vec3(0.74, 0.35, 0.30));

    midlevel = 1.0;
    uv2 = uv + vec2(t / 100.0 + 3.5, 0.0);
    h = (fbm(uv2, 8) - 0.5) * 5.0;
    layer(0.1, vec3(0.92, 0.85, 0.82));
    layer(0.0, vec3(1.0, 0.94, 0.91));

    return vec4(0.58, 0.7, 1.0, 1.0);
}

void main() {
    // OpenGL ES: gl_FragCoord 原点在左下角，与 Shadertoy 一致，无需翻转 Y
    vec2 fc = gl_FragCoord.xy;

    vec2 uv = fc / iResolution.y;
    float t = iTime * 4.0;
    vec4 bg = background(uv, t);

    vec4 fg = vec4(0.0);
    if (uv.y < 0.5) {
        for (int i = 0; i < 5; i++) {
            fg += foreground(uv, t + 4.0 * float(i) / 5.0 / 60.0) / 5.0;
        }
    }

    vec3 col = bg.rgb;

    // ---- 火车 ----
    float k;
    vec2 uv2;
    uv.y -= 0.2;

    uv2 = fract(uv * 9.0);
    float wagon = 1.0;
    wagon *= 1.0 - step(0.45, uv.x);
    wagon *= 1.0 - step(0.115, uv.y);
    wagon *= step(0.103, uv.y);
    wagon *= step(0.05, 1.0 - abs(uv2.x * 2.0 - 1.0));

    float join = 1.0;
    join *= 1.0 - step(0.45, uv.x);
    join *= 1.0 - step(0.11, uv.y);
    join *= step(0.107, uv.y);

    float roof = 1.0;
    roof *= 1.0 - step(0.45, uv.x);
    roof *= 1.0 - step(0.117, uv.y);
    roof *= step(0.11, uv.y);
    roof *= step(0.15, 1.0 - abs(uv2.x * 2.0 - 1.0));

    float loco = box(uv, 0.45, 0.5, 0.103, 0.112);
    float chem1 = box(uv, 0.49, 0.495, 0.103, 0.12);
    float chem2 = box(uv, 0.488, 0.496, 0.12, 0.123);
    float locoRoof = box(uv, 0.443, 0.47, 0.11, 0.117);

    float wheel = 1.0 - step(0.00004, dot2(uv - vec2(0.457, 0.106)));
    wheel += 1.0 - step(0.00002, dot2(uv - vec2(0.487, 0.105)));
    wheel += 1.0 - step(0.00002, dot2(uv - vec2(0.497, 0.105)));

    if (uv.x < 0.45 && uv.y > 0.025 && uv.y < 0.2) {
        wheel += 1.0 - step(0.002, dot2(uv2 - vec2(0.2, 0.95)));
        wheel += 1.0 - step(0.002, dot2(uv2 - vec2(0.8, 0.95)));
    }
    col = mix(col, vec3(0.18, 0.12, 0.15), join);
    col = mix(col, vec3(0.48, 0.19, 0.20), wagon);
    col = mix(col, vec3(0.18, 0.12, 0.15), roof);
    col = mix(col, vec3(0.38, 0.19, 0.20), loco);
    col = mix(col, vec3(0.38, 0.19, 0.20), chem1);
    col = mix(col, vec3(0.18, 0.12, 0.15), locoRoof);
    col = mix(col, vec3(0.18, 0.12, 0.15), chem2 + wheel);

    // ---- 机车烟雾 ----
    uv2 = uv + vec2(t / 5.0 + 3.5, 0.0);
    uv2.x -= t / 5.0 * 0.2;
    float h = fbm2(uv2, 8) - 0.55;

    if (uv.x < 0.49) {
        float x = -uv.x + 0.49;
        float y = abs(uv.y + h * 0.4 - 0.16 * sqrt(x) - 0.12) - 0.8 * x * exp(-x * 10.0);
        if (y < 0.0) col = vec3(1.0, 0.94, 0.91);
        if (y < -0.02) col = vec3(0.92, 0.85, 0.82);
    }

    // ---- 桥 ----
    uv2 = uv + vec2(t / 5.0 + 32.5, 0.0);
    uv2.x = fract(uv2.x * 3.0);
    k = 1.0;
    k *= smoothstep(0.001, 0.003, abs(uv2.y - pow(uv2.x - 0.5, 2.0) * 0.15 - 0.12));
    k *= min(step(0.05, 1.0 - abs(uv2.x * 2.0 - 1.0)) + step(0.17, uv2.y), 1.0);
    k *= min(smoothstep(0.02, 0.05, 1.0 - abs(uv2.x * 2.0 - 1.0)) + step(0.177, uv2.y), 1.0);
    k *= min(step(0.1, uv2.y) + smoothstep(-0.09, -0.085, -uv2.y - 0.001 / (1.0 - abs(uv2.x * 2.0 - 1.0))), 1.0);
    k *= min(smoothstep(0.05, 0.2, 1.0 - abs(fract(uv2.x * 16.0) * 2.0 - 1.0)) + step(0.12, uv2.y - pow(uv2.x - 0.5, 2.0) * 0.15) + step(-0.1, -uv2.y), 1.0);
    col = mix(vec3(0.29, 0.09, 0.08) * smoothstep(-0.08, 0.08, uv.y), col, k);

    col = mix(col, fg.rgb, fg.a);

    // ---- 着色器2：暗角 ----
    vec2 uvScreen = fc / iResolution.xy;
    col *= 0.5 + 0.5 * pow(16.0 * uvScreen.x * uvScreen.y * (1.0 - uvScreen.x) * (1.0 - uvScreen.y), 0.2);

    // ---- 底部云层过渡：复用 foreground() 第一层云的 fbm 流场，
    // 与画面底部可见云层同速、同形（iTime*4），避免速度/颜色错位 ----
    // foreground 第一层：uv.y-=0.2; uv2=uv+vec2(t/1.0+40.0, 0.0); h=(fbm-0.5)*1.7
    vec2 cloudUv = uv - vec2(0.0, 0.2) + vec2(iTime * 4.0 + 40.0, 0.0);
    float cloudH = (fbm(cloudUv, 8) - 0.5) * 1.7;
    // 云层可见边缘 = cloudH - 0.02；将其映射到屏幕底部一带做羽化
    // 加宽并抬高过渡带，让底部云层向下延伸遮住白色背景，再羽化消失
    float edgeY = clamp(0.15 + cloudH * 0.12, 0.0, 0.32);
    float fadeAlpha = smoothstep(edgeY - 0.08, edgeY + 0.08, uvScreen.y);
    col = mix(iBgColor, col, fadeAlpha);

    gl_FragColor = vec4(col, 1.0);
}
""".trimIndent()

// 回退纯色（GLES2 不支持或着色器编译失败时使用）
private val FallbackColor = Color(0xFF1a1a2e)
private val FallbackColorGL = floatArrayOf(0.102f, 0.102f, 0.180f, 1f)

/// 检测设备是否支持 OpenGL ES 2.0
private fun supportsGLES2(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return am.deviceConfigurationInfo.reqGlEsVersion >= 0x20000
}

private class WelcomeRenderer : GLSurfaceView.Renderer {
    private var program = 0
    private var positionHandle = 0
    private var resolutionHandle = 0
    private var timeHandle = 0
    private var bgColorHandle = 0
    private var channel0Handle = 0
    private var noiseTexture = 0
    private var startTime = 0L
    private var width = 1
    private var height = 1
    // 页面背景色 RGB（0..1），由 Composable 传入
    private var bgColorR = 0.102f
    private var bgColorG = 0.102f
    private var bgColorB = 0.180f

    // 全屏四边形（两个三角形组成的 TRIANGLE_STRIP）
    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
            ))
            position(0)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(
            FallbackColorGL[0], FallbackColorGL[1], FallbackColorGL[2], FallbackColorGL[3]
        )
        noiseTexture = generateNoiseTexture()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) {
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            resolutionHandle = GLES20.glGetUniformLocation(program, "iResolution")
            timeHandle = GLES20.glGetUniformLocation(program, "iTime")
            bgColorHandle = GLES20.glGetUniformLocation(program, "iBgColor")
            channel0Handle = GLES20.glGetUniformLocation(program, "iChannel0")
            startTime = SystemClock.elapsedRealtime()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // 着色器未就绪（编译失败）→ 仅显示回退纯色
        if (program == 0) return

        GLES20.glUseProgram(program)
        val time = (SystemClock.elapsedRealtime() - startTime) / 1000f
        GLES20.glUniform2f(resolutionHandle, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(timeHandle, time)
        GLES20.glUniform3f(bgColorHandle, bgColorR, bgColorG, bgColorB)

        // 绑定噪声纹理到纹理单元 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexture)
        GLES20.glUniform1i(channel0Handle, 0)

        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    /// 设置页面背景色，用于着色器底部云层过渡
    fun setBgColor(r: Float, g: Float, b: Float) {
        bgColorR = r
        bgColorG = g
        bgColorB = b
    }

    /// 生成 1024×1024 随机灰度噪声纹理，替代 Shadertoy 的 iChannel0
    private fun generateNoiseTexture(): Int {
        val size = 1024
        val buffer = ByteBuffer.allocateDirect(size * size)
        val random = java.util.Random(42)
        for (i in 0 until size * size) {
            buffer.put(random.nextInt(256).toByte())
        }
        buffer.position(0)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            size, size, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        return textures[0]
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(vsSource: String, fsSource: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vsSource)
        if (vs == 0) return 0
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        if (prog == 0) return 0
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }
}

private class WelcomeGLSurfaceView(context: Context) : GLSurfaceView(context) {
    val welcomeRenderer = WelcomeRenderer()
    init {
        setEGLContextClientVersion(2)
        setRenderer(welcomeRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

/// 欢迎页着色器背景：GLES2 可用且着色器编译成功时渲染动画背景，否则回退纯色
/// pageBgColor 为页面背景色，用于着色器底部云层向其平滑过渡
@Composable
fun WelcomeShaderBackground(
    modifier: Modifier = Modifier,
    pageBgColor: Color = FallbackColor
) {
    val context = LocalContext.current
    val gles2Supported = remember { supportsGLES2(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    if (!gles2Supported) {
        // 设备不支持 GLES2 → 纯色背景
        Box(modifier.background(pageBgColor))
        return
    }

    // 持有 GLSurfaceView 引用，用于生命周期管理与背景色传递
    var glView by remember { mutableStateOf<WelcomeGLSurfaceView?>(null) }

    // 页面背景色变化时同步到渲染器
    LaunchedEffect(pageBgColor) {
        glView?.welcomeRenderer?.setBgColor(
            pageBgColor.red, pageBgColor.green, pageBgColor.blue
        )
    }

    AndroidView(
        factory = { ctx ->
            WelcomeGLSurfaceView(ctx).also { view ->
                view.welcomeRenderer.setBgColor(
                    pageBgColor.red, pageBgColor.green, pageBgColor.blue
                )
                glView = view
            }
        },
        modifier = modifier,
        onRelease = { view -> view.onPause() }
    )

    // 跟随 Activity 生命周期暂停/恢复渲染线程
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
