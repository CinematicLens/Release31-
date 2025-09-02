
package com.squeezer.app;

import android.graphics.SurfaceTexture;
import android.opengl.*;

public class EGLImageHelper {
    public EGLDisplay display;
    public EGLContext context;
    public EGLSurface surface;

    public void initEGL(int width, int height, SurfaceTexture surfaceTexture) {
        int[] version = new int[2];
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(display, version, 0, version, 1);
        int[] configAttribs = {
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0);
        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        };
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        int[] surfaceAttribs = {
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        };
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);
        EGL14.eglMakeCurrent(display, surface, surface, context);
    }

    public void release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
    }
}
