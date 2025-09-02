// File: EGLHelper.java
package com.squeezer.app;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

public class EGLHelper {
    // Not in the public SDK constants:
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    public EGLHelper(Surface outputSurface) {
        // 1) Display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("❌ Unable to get EGL display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("❌ Unable to initialize EGL");
        }

        // 2) Config (recordable for MediaCodec input surface)
        int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,    EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE,        8,
                EGL14.EGL_GREEN_SIZE,      8,
                EGL14.EGL_BLUE_SIZE,       8,
                EGL14.EGL_ALPHA_SIZE,      8,
                EGL_RECORDABLE_ANDROID,    1,    // <<< critical for encoder surfaces
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.length, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new RuntimeException("❌ Unable to find suitable EGLConfig");
        }
        EGLConfig eglConfig = configs[0];

        // 3) Context
        int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("❌ Failed to create EGL context");
        }

        // 4) Surface
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("❌ Failed to create EGL surface");
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("❌ eglMakeCurrent failed");
        }
    }

    /**
     * Draw a frame. Safe to call every decoded frame.
     * This method binds textures/ uniforms in a conservative way; it won’t fight with your caller.
     */
    public void drawFrame(int program,
                          int lutTextureId, int lutSize,
                          int width, int height,
                          int oesTextureId,
                          int videoRotation) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        // Bind external OES frame (unit 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        int uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture");
        if (uTextureLoc >= 0) GLES20.glUniform1i(uTextureLoc, 0);

        // Bind LUT (unit 1) if provided
        boolean useLut = lutTextureId != 0 && lutSize > 1;
        if (useLut) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId);

            int uLUTLoc = GLES20.glGetUniformLocation(program, "lutTexture");
            if (uLUTLoc >= 0) GLES20.glUniform1i(uLUTLoc, 1);

            int uSizeLoc = GLES20.glGetUniformLocation(program, "uLUTSize");
            if (uSizeLoc >= 0) GLES20.glUniform1f(uSizeLoc, (float) lutSize);

            // uApplyLUT is a float in your shader
            int uApplyLUT = GLES20.glGetUniformLocation(program, "uApplyLUT");
            if (uApplyLUT >= 0) GLES20.glUniform1f(uApplyLUT, 1f);
        }

        // MVP for rotation
        float[] mvpMatrix = new float[16];
        Matrix.setIdentityM(mvpMatrix, 0);

        if (videoRotation == 90) {
            Matrix.rotateM(mvpMatrix, 0, 90, 0f, 0f, 1f);
            Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f);  // keep orientation upright
        } else if (videoRotation == 180) {
            Matrix.rotateM(mvpMatrix, 0, 180, 0f, 0f, 1f);
        } else if (videoRotation == 270) {
            Matrix.rotateM(mvpMatrix, 0, 270, 0f, 0f, 1f);
        }

        int mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        if (mvpMatrixHandle >= 0) {
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
        }

        ShaderUtils.drawFullScreenQuad(program, mvpMatrix);
        GLES20.glFlush();
    }

    /** Swap the encoder input surface buffers after drawing. */
    public void swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    /** NEW: Stamp frame time in microseconds; internally converted to nanoseconds for EGLExt. */
    public void setPresentationTimeUs(long ptsUs) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L);
    }

    /** Keeps your existing ns API if some callers already use it. */
    public void setPresentationTime(long timestampNs) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs);
    }

    public void release() {
        if (eglDisplay == null) return;
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = null;
        }
        if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = null;
        }
        EGL14.eglTerminate(eglDisplay);
        eglDisplay = null;
    }
}
