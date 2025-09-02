// File: LUTPreviewHelper.java
package com.squeezer.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

/** Creates a persistent video preview session with live LUT swapping. */
public final class LUTPreviewHelper {

    private LUTPreviewHelper() {}

    /** Handle you keep to update LUT/tint/contrast/saturation without rebuilding. */
    public static final class Session {
        final FrameLayout container;
        final GLSurfaceView glView;
        final LUTPreviewRenderer renderer;
        MediaPlayer player; // becomes non-null once prepared
        boolean released = false;

        Session(FrameLayout container, GLSurfaceView glView, LUTPreviewRenderer renderer) {
            this.container = container;
            this.glView = glView;
            this.renderer = renderer;
            // keep a reference on the view for future cleanup
            glView.setTag(this);
        }

        public void setLUT(String lutId) {
            if (released) return;
            renderer.setLUT(lutId); // reloads texture on GL thread & requestRender()
        }

        public void setGrade(float tint, float contrast, float saturation) {
            if (released) return;
            renderer.setTint(tint);
            renderer.setContrast(contrast);
            renderer.setSaturation(saturation);
            safeRequestRender();
        }

        public void setLoop(boolean loop) {
            if (released || player == null) return;
            try { player.setLooping(loop); } catch (Throwable ignored) {}
        }

        public void setMuted(boolean mute) {
            if (released || player == null) return;
            try { player.setVolume(mute ? 0f : 1f, mute ? 0f : 1f); } catch (Throwable ignored) {}
        }

        // ---- Advanced controls (forwarded to renderer via reflection; no-op if not implemented) ----
        public void setExposure(float ev) { callRenderer("setExposure", new Class[]{float.class}, ev); safeRequestRender(); }
        public void setVibrance(float v) { callRenderer("setVibrance", new Class[]{float.class}, v); safeRequestRender(); }
        public void setTempTint(float temp, float greenMagenta) {
            callRenderer("setTempTint", new Class[]{float.class, float.class}, temp, greenMagenta);
            safeRequestRender();
        }
        public void setHighlightRoll(float amt) { callRenderer("setHighlightRoll", new Class[]{float.class}, amt); safeRequestRender(); }
        public void setVignette(float strength, float softness) {
            callRenderer("setVignette", new Class[]{float.class, float.class}, strength, softness);
            safeRequestRender();
        }

        // Optional helpers
        public void pause() {
            if (released || player == null) return;
            try { if (player.isPlaying()) player.pause(); } catch (Throwable ignored) {}
        }

        public void resume() {
            if (released || player == null) return;
            try { if (!player.isPlaying()) player.start(); } catch (Throwable ignored) {}
        }

        public void seekToMs(int ms) {
            if (released || player == null) return;
            try { player.seekTo(ms); } catch (Throwable ignored) {}
        }

        public void release() {
            if (released) return;
            released = true;
            try { if (player != null) { player.stop(); player.release(); } } catch (Throwable ignored) {}
            if (container.getChildCount() > 0) container.removeAllViews();
        }

        private void safeRequestRender() {
            try { glView.requestRender(); } catch (Throwable ignored) {}
        }

        private void callRenderer(String name, Class<?>[] sig, Object... args) {
            try {
                java.lang.reflect.Method m = renderer.getClass().getMethod(name, sig);
                m.invoke(renderer, args);
            } catch (Throwable ignored) {
                // renderer doesn't implement it yet — safe no-op
            }
        }
    }

    /** Create a video preview session and start playback. */
    public static Session showPreview(
            Context ctx,
            Uri videoUri,
            String lutId,
            FrameLayout container,
            boolean mute,
            float tint,
            float contrast,
            float saturation,
            boolean loop
    ) {
        // Clean any previous session
        cleanupExisting(container);

        // GLSurfaceView + renderer
        GLSurfaceView glView = new GLSurfaceView(ctx);
        glView.setEGLContextClientVersion(2);

        LUTPreviewRenderer renderer = new LUTPreviewRenderer(ctx, videoUri, lutId);
        renderer.setGLSurfaceView(glView);
        renderer.setTint(tint);
        renderer.setContrast(contrast);
        renderer.setSaturation(saturation);

        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        container.addView(glView, lp);

        // Session without player yet
        Session session = new Session(container, glView, renderer);

        // Player will be created when the GL surface is ready
        renderer.setOnSurfaceReady(() -> {
            try {
                MediaPlayer mp = new MediaPlayer();

                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                mp.setAudioAttributes(attrs);
                mp.setVolume(mute ? 0f : 1f, mute ? 0f : 1f);
                mp.setLooping(loop);
                mp.setDataSource(ctx, videoUri);
                mp.setSurface(renderer.getDecoderSurface());

                mp.setOnVideoSizeChangedListener((p, w, h) -> {
                    // renderer already reads metadata rotation; we pass 0 (renderer keeps its own)
                    renderer.setVideoDimensions(w, h, 0);
                    try { glView.requestRender(); } catch (Throwable ignored) {}
                });

                mp.setOnPreparedListener(p -> {
                    session.player = p; // now the session owns the prepared player
                    try { p.start(); } catch (Throwable ignored) {}
                    try { glView.requestRender(); } catch (Throwable ignored) {}
                });

                mp.setOnCompletionListener(p -> {
                    if (!loop) {
                        try { glView.requestRender(); } catch (Throwable ignored) {}
                    }
                });

                mp.prepareAsync();
            } catch (Throwable t) {
                Toast.makeText(ctx, "Video init failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        return session;
    }

    private static void cleanupExisting(FrameLayout container) {
        if (container.getChildCount() == 0) return;
        Object tag = container.getChildAt(0).getTag();
        if (tag instanceof Session) {
            ((Session) tag).release();
        } else {
            container.removeAllViews();
        }
    }
}
