package app.tvmirror.phone;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 帧率节拍器：VirtualDisplay → SurfaceTexture → GL 按目标帧率渲染 → 编码器输入 Surface。
 *
 * 为什么需要它：VirtualDisplay 是显示合成器，会按面板刷新率（这台平板 144Hz）不停推帧。
 * `MediaFormat.KEY_MAX_FPS_TO_ENCODER` 与 `Surface.setFrameRate` 对合成器输入都不起作用，
 * 实测编码器始终按 144fps 出帧。帧率过高会把码率预算摊薄到每一帧上，
 * 而相邻帧之间几乎没有差别，编码器判定「低复杂度」，于是只给很少的比特——画质因此变糊。
 *
 * 实现要点：EGL 初始化、SurfaceTexture 创建、updateTexImage 必须都在同一个线程，
 * 否则会报 `invalid current EGLDisplay`。所以这里把所有 GL 状态都放在渲染线程里建，
 * 创建方通过 latch 等它就绪后再拿生产者 Surface。
 */
final class GlFramePacer implements SurfaceTexture.OnFrameAvailableListener, Runnable {

    private static final String TAG = "TVMirror";

    static GlFramePacer create(Surface encoderSurface, int width, int height, int fps)
            throws Exception {
        GlFramePacer pacer = new GlFramePacer(encoderSurface, width, height, fps);
        pacer.startAndWait();
        return pacer;
    }

    private final Surface encoderSurface;
    private final int width;
    private final int height;
    private volatile int targetFps;
    private volatile boolean running = true;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private SurfaceTexture surfaceTexture;
    private volatile Surface producerSurface;
    private int textureId;
    private int program;
    private int uSTMatrixLoc;
    private int aPositionLoc;
    private int aTexCoordLoc;
    private final float[] transform = new float[16];
    private final Semaphore frameSync = new Semaphore(0);
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile String initError;
    private Thread renderThread;

    private GlFramePacer(Surface encoderSurface, int width, int height, int fps) {
        this.encoderSurface = encoderSurface;
        this.width = width;
        this.height = height;
        this.targetFps = Math.max(1, fps);
    }

    /** 供 VirtualDisplay 使用的生产者 Surface。 */
    Surface producerSurface() {
        return producerSurface;
    }

    void setFrameRate(int fps) {
        this.targetFps = Math.max(1, fps);
    }

    private void startAndWait() throws Exception {
        renderThread = new Thread(this, "tvmirror-pacer");
        renderThread.start();
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("节拍器初始化超时");
        }
        if (initError != null) {
            throw new IllegalStateException(initError);
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameSync.release();
    }

    @Override
    public void run() {
        try {
            initGl();
        } catch (Exception e) {
            initError = e.getMessage();
            ready.countDown();
            cleanupGl();
            return;
        }
        ready.countDown();

        long lastDrawUs = 0;
        try {
            while (running) {
                try {
                    if (!frameSync.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                        continue;
                    }
                } catch (InterruptedException e) {
                    break;
                }
                if (!running) {
                    break;
                }
                // 每帧都必须 updateTexImage，否则生产端拿不回缓冲会卡住整个虚拟屏
                surfaceTexture.updateTexImage();
                long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
                long interval = 1_000_000L / Math.max(1, targetFps);
                if (nowUs - lastDrawUs < interval) {
                    continue;
                }
                lastDrawUs = nowUs;
                drawFrame();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "节拍器渲染异常: " + e.getMessage());
        } finally {
            cleanupGl();
        }
    }

    private void drawFrame() {
        surfaceTexture.getTransformMatrix(transform);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, transform, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0,
                quadPositions());
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords());

        // 用采集时间戳作为帧的呈现时间，编码器 PTS 因此仍是 CLOCK_MONOTONIC 时基
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                surfaceTexture.getTimestamp());
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTexCoordLoc);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private void initGl() throws Exception {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("无法获取 EGLDisplay");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("EGL 初始化失败");
        }

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new IllegalStateException("没有可用的 EGLConfig");
        }

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("EGLContext 创建失败");
        }

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface,
                surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("EGLSurface 创建失败（编码器输入 Surface 不可用）");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IllegalStateException("eglMakeCurrent 失败");
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 必须在本线程（已 makeCurrent）创建，否则 updateTexImage 会报 invalid EGLDisplay
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(width, height);
        surfaceTexture.setOnFrameAvailableListener(this);
        producerSurface = new Surface(surfaceTexture);

        program = buildProgram();
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix");
        Matrix.setIdentityM(transform, 0);
    }

    void release() {
        running = false;
        frameSync.release();
        Thread t = renderThread;
        renderThread = null;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 兜底：线程若没能自己清理（比如卡在 updateTexImage），这里再收一次
        cleanupGl();
    }

    private synchronized void cleanupGl() {
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (producerSurface != null) {
            producerSurface.release();
            producerSurface = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        program = 0;
        textureId = 0;
    }

    private static java.nio.FloatBuffer quadPositions() {
        float[] v = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        java.nio.FloatBuffer b = java.nio.ByteBuffer
                .allocateDirect(v.length * 4).order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    private static java.nio.FloatBuffer quadTexCoords() {
        float[] v = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
        java.nio.FloatBuffer b = java.nio.ByteBuffer
                .allocateDirect(v.length * 4).order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    private static int buildProgram() {
        String vertex = "attribute vec4 aPosition;\n"
                + "attribute vec4 aTexCoord;\n"
                + "uniform mat4 uSTMatrix;\n"
                + "varying vec2 vTexCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aPosition;\n"
                + "  vTexCoord = (uSTMatrix * aTexCoord).xy;\n"
                + "}\n";
        String fragment = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform samplerExternalOES sTexture;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                + "}\n";
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            throw new IllegalStateException("着色器链接失败: " + GLES20.glGetProgramInfoLog(p));
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            throw new IllegalStateException("着色器编译失败: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
