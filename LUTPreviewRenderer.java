// File: LUTPreviewRenderer.java
package com.squeezer.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class LUTPreviewRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "LUTRenderer";

    private final Context context;
    private final Uri videoUri;

    /** "asset:Name.cube", "file:/abs/path.cube", or plain asset name. */
    private String lutId;
    private boolean applyLUT;            // whether a LUT id is present & texture loaded
    private boolean gradeEnabled = true; // sliders enabled (stacked on top of LUT)

    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private int oesTextureId;
    private int program;                 // active GL program
    private int lutTextureId;
    private int lutSize;

    // --- Basic grade (already wired)
    private float tint = 0.0f;
    private float contrast = 1.0f;
    private float saturation = 1.0f;

    // Advanced
    private float exposure = 0.0f;
    private float vibrance = 0.0f;
    private float temp = 0.0f;
    private float greenMagenta = 0.0f;
    private float highlightRoll = 0.0f;
    private float vignetteStrength = 0.0f;
    private float vignetteSoftness = 0.0f;

    private Runnable surfaceReadyCallback;

    private final float[] vertexCoords = {
            -1f, -1f,   1f, -1f,
            -1f,  1f,   1f,  1f
    };
    private final float[] texCoords = {
            0f, 1f,   1f, 1f,
            0f, 0f,   1f, 0f
    };
    private final FloatBuffer vertexBuffer;
    private final FloatBuffer texBuffer;

    private GLSurfaceView glSurfaceView; // set by host
    private final float[] mvpMatrix = new float[16];

    private int videoWidth = 0;
    private int videoHeight = 0;
    private int videoRotation = 0;
    private boolean isPortraitSurface = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    // ---------- Samsung S23/S24 safety: ready gate + caches ----------
    private final AtomicBoolean surfaceReady = new AtomicBoolean(false);

    // Cache UI-updates that arrive before GL surface exists (basic)
    private Float pendingTint = null;
    private Float pendingContrast = null;
    private Float pendingSaturation = null;
    private Boolean pendingGradeEnabled = null;
    private boolean pendingReloadLut = false;

    // Cache advanced params too
    private Float pendingExposure = null;
    private Float pendingVibrance = null;
    private Float pendingTemp = null;
    private Float pendingGreenMagenta = null;
    private Float pendingHighlightRoll = null;
    private Float pendingVignetteStrength = null;
    private Float pendingVignetteSoftness = null;

    public LUTPreviewRenderer(Context context, Uri videoUri, String initialLutId) {
        this.context = context;
        this.videoUri = videoUri;
        this.lutId = initialLutId;
        this.applyLUT = (initialLutId != null);

        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertexCoords).position(0);

        texBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texBuffer.put(texCoords).position(0);

        this.videoRotation = getRotationDegrees(context, videoUri);
        this.isPortraitSurface = (videoRotation == 90 || videoRotation == 270);
        Log.i(TAG, "🎞 Detected " + (isPortraitSurface ? "portrait" : "landscape")
                + " video (rotation: " + videoRotation + "°)");
    }

    public void setGLSurfaceView(GLSurfaceView view) { this.glSurfaceView = view; }

    public Surface getDecoderSurface() { return decoderSurface; }

    public SurfaceTexture getSurfaceTexture() { return surfaceTexture; }

    public void setOnSurfaceReady(Runnable callback) { this.surfaceReadyCallback = callback; }

    public void setVideoDimensions(int width, int height, int rotationDegrees) {
        this.videoWidth = width;
        this.videoHeight = height;
        setVideoRotation(rotationDegrees);
        if (glSurfaceView != null) {
            glSurfaceView.queueEvent(this::updateOesDefaultBufferSize);
        }
    }

    public void setVideoRotation(int rotationDegrees) {
        this.videoRotation = rotationDegrees;
        this.isPortraitSurface = (rotationDegrees == 90 || rotationDegrees == 270);
        Log.i(TAG, "🎞 Rotation set manually: "
                + (isPortraitSurface ? "Portrait" : "Landscape")
                + " (" + rotationDegrees + "°)");
    }

    // ---------- Safe setters: cache if surface not ready, then request render safely ----------

    public void setTint(float tint) {
        synchronized (this) { pendingTint = tint; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.tint = tint;
        requestRenderSafe();
    }

    public void setContrast(float contrast) {
        synchronized (this) { pendingContrast = contrast; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.contrast = contrast;
        requestRenderSafe();
    }

    public void setSaturation(float saturation) {
        synchronized (this) { pendingSaturation = saturation; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.saturation = saturation;
        requestRenderSafe();
    }

    public void setGradeEnabled(boolean enabled) {
        synchronized (this) { pendingGradeEnabled = enabled; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.gradeEnabled = enabled;
        requestRenderSafe();
    }

    // ---- Advanced (new) ----
    public void setExposure(float ev) {
        synchronized (this) { pendingExposure = ev; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.exposure = ev;
        requestRenderSafe();
    }

    public void setVibrance(float v) {
        synchronized (this) { pendingVibrance = v; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.vibrance = v;
        requestRenderSafe();
    }

    public void setTempTint(float temp, float greenMagenta) {
        synchronized (this) { pendingTemp = temp; pendingGreenMagenta = greenMagenta; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.temp = temp;
        this.greenMagenta = greenMagenta;
        requestRenderSafe();
    }

    public void setHighlightRoll(float amt) {
        synchronized (this) { pendingHighlightRoll = amt; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.highlightRoll = amt;
        requestRenderSafe();
    }

    public void setVignette(float strength, float softness) {
        synchronized (this) { pendingVignetteStrength = strength; pendingVignetteSoftness = softness; }
        if (!surfaceReady.get()) { requestRenderSafe(); return; }
        this.vignetteStrength = strength;
        this.vignetteSoftness = softness;
        requestRenderSafe();
    }

    public void setLUT(String newLutId) {
        this.lutId = newLutId;
        this.applyLUT = (newLutId != null);
        if (!surfaceReady.get()) {
            pendingReloadLut = true;
            Log.w(TAG, "setLUT called before surface ready; will load on surface creation.");
            requestRenderSafe();
            return;
        }
        if (glSurfaceView != null) {
            glSurfaceView.queueEvent(() -> {
                reloadLutTexture();
                requestRenderSafe();
            });
        }
    }

    // Null-safe, OEM-safe requestRender
    private void requestRenderSafe() {
        final GLSurfaceView v = glSurfaceView;
        if (v == null) return;
        v.post(() -> {
            try {
                if (surfaceReady.get()) v.requestRender();
            } catch (Throwable ignored) {
                // Prevent fatal crashes on devices where GLSurfaceView internals vary
            }
        });
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // OES video texture & Surface
        oesTextureId = ShaderUtils.createExternalTexture();
        surfaceTexture = new SurfaceTexture(oesTextureId);
        surfaceTexture.setOnFrameAvailableListener(this);
        decoderSurface = new Surface(surfaceTexture);

        // Compile main (stacking) shader
        String vs = ShaderUtils.getVertexShaderCode();
        String fs = ShaderUtils.getFragmentShaderWithLUT(); // must match float flags
        program = ShaderUtils.createProgram(vs, fs);

        if (program == 0) {
            // 🔁 Fallback to a plain OES shader to avoid crashing
            Log.e(TAG, "LUT shader program = 0. Falling back to OES-only shader.");
            program = ShaderUtils.createProgram(vs, ShaderUtils.getFragmentShaderOESOnly());
        }

        // Load LUT texture (if any)
        reloadLutTexture();

        // Initial buffer size (prevents black on some devices)
        updateOesDefaultBufferSize();

        GLES20.glUseProgram(program);
        checkGlError("onSurfaceCreated glUseProgram");

        // Mark ready and flush any cached UI parameters safely
        surfaceReady.set(true);
        flushPendingParamsOnGlThread();

        if (surfaceReadyCallback != null) {
            main.post(surfaceReadyCallback);
        }

        requestRenderSafe();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);

        int cw = videoWidth;
        int ch = videoHeight;
        if (isPortraitSurface) { int t = cw; cw = ch; ch = t; }

        float viewAspect = (float) width / height;
        float videoAspect = (cw > 0 && ch > 0) ? (float) cw / ch : viewAspect;

        float scaleX = 1f, scaleY = 1f;
        if (videoAspect > viewAspect) scaleY = viewAspect / videoAspect;
        else scaleX = videoAspect / viewAspect;

        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f);

        if (videoRotation == 90) {
            Matrix.rotateM(mvpMatrix, 0, 270, 0f, 0f, 1f);
            Matrix.scaleM(mvpMatrix, 0, 1f, -1f, 1f);
        } else if (videoRotation == 180 && !isPortraitSurface) {
            Matrix.rotateM(mvpMatrix, 0, 180, 0f, 0f, 1f);
        }

        GLES20.glUseProgram(program);
        int mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        if (mvpHandle >= 0) {
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0);
        }
        checkGlError("onSurfaceChanged set uMVPMatrix");

        updateOesDefaultBufferSize();

        if (context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.w(TAG, "📱 For better preview, rotate device to landscape.");
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (surfaceTexture != null) surfaceTexture.updateTexImage();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        int aPos = GLES20.glGetAttribLocation(program, "aPosition");
        int aTex = GLES20.glGetAttribLocation(program, "aTexCoord");

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        // Video (OES) on unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        int uTex = GLES20.glGetUniformLocation(program, "uTexture");
        if (uTex >= 0) GLES20.glUniform1i(uTex, 0);

        // Flags as floats (safer across drivers)
        final boolean lutActive = applyLUT && lutTextureId > 0;
        setFloatUniform("uApplyLUT",   lutActive ? 1.0f : 0.0f);
        setFloatUniform("uApplyGrade", gradeEnabled ? 1.0f : 0.0f);

        // Grade uniforms (basic)
        setFloatUniform("uTint",       tint);
        setFloatUniform("uContrast",   contrast);
        setFloatUniform("uSaturation", saturation);

        // Advanced uniforms (safe if absent in shader)
        setFloatUniform("uExposure",          exposure);
        setFloatUniform("uVibrance",          vibrance);
        setFloatUniform("uTemp",              temp);
        setFloatUniform("uTintGM",            greenMagenta);
        setFloatUniform("uHighlightRoll",     highlightRoll);
        setFloatUniform("uVignetteStrength",  vignetteStrength);
        setFloatUniform("uVignetteSoftness",  vignetteSoftness);

        // Bind LUT (unit 1) if active
        if (lutActive) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId);
            int lutLoc = GLES20.glGetUniformLocation(program, "lutTexture");
            if (lutLoc >= 0) GLES20.glUniform1i(lutLoc, 1);
            setFloatUniform("uLUTSize", (float) lutSize);
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        requestRenderSafe();
    }

    // -------------------- helpers --------------------

    private void flushPendingParamsOnGlThread() {
        if (glSurfaceView == null) return;
        glSurfaceView.queueEvent(() -> {
            // LUT reload requested before readiness?
            if (pendingReloadLut) {
                pendingReloadLut = false;
                reloadLutTexture();
            }

            // Apply cached params to fields used in onDrawFrame
            Float t, c, s; Boolean g;
            Float ex, vib, te, gm, hr, vgS, vgSo;
            synchronized (this) {
                t  = pendingTint;            pendingTint = null;
                c  = pendingContrast;        pendingContrast = null;
                s  = pendingSaturation;      pendingSaturation = null;
                g  = pendingGradeEnabled;    pendingGradeEnabled = null;

                ex = pendingExposure;        pendingExposure = null;
                vib= pendingVibrance;        pendingVibrance = null;
                te = pendingTemp;            pendingTemp = null;
                gm = pendingGreenMagenta;    pendingGreenMagenta = null;
                hr = pendingHighlightRoll;   pendingHighlightRoll = null;
                vgS= pendingVignetteStrength;pendingVignetteStrength = null;
                vgSo= pendingVignetteSoftness;pendingVignetteSoftness = null;
            }
            if (t  != null) this.tint = t;
            if (c  != null) this.contrast = c;
            if (s  != null) this.saturation = s;
            if (g  != null) this.gradeEnabled = g;

            if (ex != null) this.exposure = ex;
            if (vib!= null) this.vibrance = vib;
            if (te != null) this.temp = te;
            if (gm != null) this.greenMagenta = gm;
            if (hr != null) this.highlightRoll = hr;
            if (vgS!= null) this.vignetteStrength = vgS;
            if (vgSo!= null) this.vignetteSoftness = vgSo;

            // Also ensure buffer size is correct when we become ready
            updateOesDefaultBufferSize();
        });
    }

    private void setFloatUniform(String name, float v) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform1f(loc, v);
    }

    private void reloadLutTexture() {
        if (lutTextureId != 0) {
            int[] del = new int[]{ lutTextureId };
            GLES20.glDeleteTextures(1, del, 0);
            lutTextureId = 0;
        }
        lutSize = 33;

        if (lutId == null) {
            applyLUT = false;
            return;
        }

        try (InputStream in = LutManager.openLutStream(context, lutId)) {
            Pair<Integer, Integer> info = LUTLoader.loadCubeLUT(in);
            lutTextureId = info.first != null ? info.first : 0;
            lutSize = info.second != null ? info.second : 33;
            applyLUT = (lutTextureId != 0);
            Log.d(TAG, "✅ LUT loaded from " + lutId + " (size=" + lutSize + ")");
        } catch (Throwable t) {
            Log.e(TAG, "❌ Failed to load LUT (" + lutId + "): " + t.getMessage(), t);
            lutTextureId = 0;
            applyLUT = false;
        }
    }

    private void updateOesDefaultBufferSize() {
        if (surfaceTexture == null) return;
        if (videoWidth <= 0 || videoHeight <= 0) return;

        int bw = videoWidth;
        int bh = videoHeight;
        if (isPortraitSurface) { bw = videoHeight; bh = videoWidth; }

        surfaceTexture.setDefaultBufferSize(bw, bh);
        Log.d(TAG, "OES default buffer set to " + bw + "x" + bh);
    }

    private int getRotationDegrees(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String r = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return (r != null) ? Integer.parseInt(r) : 0;
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Failed to read rotation: " + e.getMessage());
            return 0;
        } finally {
            try { retriever.release(); } catch (IOException ignored) {}
        }
    }

    private void checkGlError(String where) {
        int err;
        if ((err = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, where + " GL_ERROR: 0x" + Integer.toHexString(err));
        }
    }
}
