package com.squeezer.app;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import java.io.IOException;

public class VideoPlayerWrapper implements SurfaceTexture.OnFrameAvailableListener {

    private final Context context;
    private final GLSurfaceView glSurfaceView;
    private final LUTPreviewRenderer renderer;
    private MediaPlayer mediaPlayer;
    private Uri videoUri;
    private Surface surface;
    private SurfaceTexture surfaceTexture;
    private boolean isPortrait = false;

    public VideoPlayerWrapper(Context context, GLSurfaceView glSurfaceView, LUTPreviewRenderer renderer) {
        this.context = context;
        this.glSurfaceView = glSurfaceView;
        this.renderer = renderer;
    }

    public void setVideoUri(Uri uri) {
        this.videoUri = uri;
        preparePlayer();
    }

    public boolean isPortrait() {
        return isPortrait;
    }

    public void setRotationAwareSize(int videoWidth, int videoHeight) {
        glSurfaceView.post(() -> {
            int screenWidth = glSurfaceView.getWidth();  // container width

            int finalWidth = screenWidth;
            int finalHeight;

            if (videoHeight > videoWidth) {
                // Portrait video – keep height dominant
                finalHeight = screenWidth * 16 / 9;
            } else {
                // Landscape video – regular 16:9
                finalHeight = screenWidth * 9 / 16;
            }

            glSurfaceView.getLayoutParams().width = finalWidth;
            glSurfaceView.getLayoutParams().height = finalHeight;
            glSurfaceView.requestLayout();
        });
    }

    private int getVideoRotation(Context context, Uri videoUri) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(context, videoUri);
            String rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            retriever.release();
            return rotationStr != null ? Integer.parseInt(rotationStr) : 0;
        } catch (Exception e) {
            Log.w("VideoPlayer", "⚠️ Failed to get rotation: " + e.getMessage());
            return 0;
        }
    }

    private void preparePlayer() {
        mediaPlayer = new MediaPlayer();
        try {
            Log.d("VideoPlayer", "📹 Video URI: " + videoUri);
            mediaPlayer.setDataSource(context, videoUri);

            surfaceTexture = renderer.getSurfaceTexture();
            if (surfaceTexture == null) {
                showToast("❌ SurfaceTexture is null, cannot preview.");
                Log.e("VideoPlayer", "❌ SurfaceTexture is null");
                return;
            }

            surfaceTexture.setOnFrameAvailableListener(this);
            surface = new Surface(surfaceTexture);
            mediaPlayer.setVolume(0f, 0f);

            mediaPlayer.setSurface(surface);
            surface.release();

            mediaPlayer.setOnPreparedListener(mp -> {
                mp.seekTo(0);
                mp.start();
                Log.d("VideoPlayer", "✅ MediaPlayer prepared");

                int durationMs = mp.getDuration();
                if (durationMs > 30000) {
                    // showToast("Preview limited to first 10 seconds");
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        mediaPlayer.seekTo(0);
                    }
                }, Math.min(durationMs, 30000));
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                showToast("❌ MediaPlayer error: " + what + ", " + extra);
                Log.e("VideoPlayer", "❌ MediaPlayer error: what=" + what + ", extra=" + extra);
                return true;
            });

            mediaPlayer.setLooping(true);
            mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
                Log.d("VideoPlayer", "📐 Video size: " + width + "x" + height);
                int rotation = getVideoRotation(context, videoUri);
                isPortrait = (rotation == 90 || rotation == 270);

                renderer.setVideoRotation(rotation); // ✅ Inform renderer

                if (isPortrait) {
                    setRotationAwareSize(height, width);
                } else {
                    setRotationAwareSize(width, height);
                }
            });

            mediaPlayer.prepareAsync();

        } catch (IOException e) {
            showToast("❌ Error loading video: " + e.getMessage());
            Log.e("VideoPlayer", "❌ IOException: " + e.getMessage());
        } catch (Exception e) {
            showToast("❌ General error: " + e.getMessage());
            Log.e("VideoPlayer", "❌ General Exception: " + e.getMessage());
        }
    }

    private void showToast(String message) {
        if (context != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.w("Toast", "⚠️ Failed to show toast: " + e.getMessage());
                }
            });
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        Log.d("VideoPlayer", "🖼 Frame available – triggering render");
        glSurfaceView.requestRender();
    }

    public void release() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }

        if (surface != null) {
            surface.release();
            surface = null;
        }
    }
}
