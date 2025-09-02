// File: LUTProcessor.java
package com.squeezer.app;

import static android.view.View.INVISIBLE;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;

public class LUTProcessor {
    private static final String TAG = "LUT";
    static int frameIndex = 0;
    private static boolean signaledEOS;
    private float[] mvpMatrix = new float[16];
    static long totalDurationUs = 1;
    private static Uri videoUri = null;

    // ---------------- Advanced grade defaults (used by simple process(...)) ----------------
    private static float sExposure = 0f;
    private static float sVibrance = 0f;
    private static float sTemp = 0f;
    private static float sGreenMagenta = 0f;
    private static float sHighlightRoll = 0f;
    private static float sVignetteStrength = 0f;
    private static float sVignetteSoftness = 0f;

    /** Optional: call this before process(...) if you want vignette (and other advanced params) when using the simple overload. */
    public static void setAdvancedGrade(float exposure, float vibrance, float temp, float greenMagenta,
                                        float highlightRoll, float vignetteStrength, float vignetteSoftness) {
        sExposure = exposure;
        sVibrance = vibrance;
        sTemp = temp;
        sGreenMagenta = greenMagenta;
        sHighlightRoll = highlightRoll;
        sVignetteStrength = vignetteStrength;
        sVignetteSoftness = vignetteSoftness;
    }

    // ---------------- Public API (simple) ----------------
    // Matches your current call site (tint + contrastΔ + saturationΔ).
    // To get vignette working without changing the call, set values via setAdvancedGrade(...) first.
    public void process(Context context,
                        Uri videoUri1,
                        String lutId,
                        File outputPathfile,
                        float tint, float contrastDelta, float saturationDelta,
                        ProgressBar circularProgressBar, TextView progressText, TextView progressLabel) throws Exception {
        processInternal(context, videoUri1, lutId, outputPathfile,
                tint, contrastDelta, saturationDelta,
                sExposure, sVibrance, sTemp, sGreenMagenta,
                sHighlightRoll, sVignetteStrength, sVignetteSoftness,
                circularProgressBar, progressText, progressLabel);
    }

    // ---------------- Public API (advanced — best for full control) ----------------
    public void processAdvanced(Context context,
                                Uri videoUri1,
                                String lutId,
                                File outputPathfile,
                                float tint, float contrastDelta, float saturationDelta,
                                float exposure, float vibrance, float temp, float greenMagenta,
                                float highlightRoll, float vignetteStrength, float vignetteSoftness,
                                ProgressBar circularProgressBar, TextView progressText, TextView progressLabel) throws Exception {
        processInternal(context, videoUri1, lutId, outputPathfile,
                tint, contrastDelta, saturationDelta,
                exposure, vibrance, temp, greenMagenta,
                highlightRoll, vignetteStrength, vignetteSoftness,
                circularProgressBar, progressText, progressLabel);
    }

    // ---------------- Helpers ----------------
    private static void set1f(int loc, float v) { if (loc >= 0) GLES20.glUniform1f(loc, v); }
    private static void set1i(int loc, int v)   { if (loc >= 0) GLES20.glUniform1i(loc, v); }
    private static void set2f(int loc, float a, float b) { if (loc >= 0) GLES20.glUniform2f(loc, a, b); }

    private static int safeGetInt(MediaFormat f, String key, int def) {
        try { return f.containsKey(key) ? f.getInteger(key) : def; } catch (Throwable t) { return def; }
    }
    private static long safeGetLong(MediaFormat f, String key, long def) {
        try { return f.containsKey(key) ? f.getLong(key) : def; } catch (Throwable t) { return def; }
    }

    private static String sanitizeLutId(String id) {
        if (id == null) return null;
        String s = id.trim();
        if (s.isEmpty()) return null;
        if ("none".equalsIgnoreCase(s)) return null;
        return s;
    }
    private static boolean isLutRequested(String id) {
        return id != null && !id.trim().isEmpty();
    }

    // ---------------- Core implementation ----------------
    private void processInternal(Context context,
                                 Uri videoUri1,
                                 String lutId,
                                 File outFile,
                                 float tint, float contrastDelta, float saturationDelta,
                                 float exposure, float vibrance, float temp, float greenMagenta,
                                 float highlightRoll, float vignetteStrength, float vignetteSoftness,
                                 ProgressBar circularProgressBar, TextView progressText, TextView progressLabel) throws Exception {

        final Handler mainHandler = new Handler(context.getMainLooper());
        videoUri = videoUri1;
        frameIndex = 0;

        mainHandler.post(() -> {
            if (circularProgressBar != null) {
                circularProgressBar.setVisibility(android.view.View.VISIBLE);
                circularProgressBar.setProgress(0);
            }
            if (progressText != null) progressText.setText("0%");
            if (progressLabel != null) progressLabel.setText("Exporting…");
        });

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        SurfaceTexture surfaceTexture = null;
        Surface decoderSurface = null;
        EGLHelper eglHelper = null;
        MediaMuxer muxer = null;

        boolean muxerStarted = false;
        int videoTrackIndexOut = -1;
        int audioTrackIndex = -1;
        int storedAudioTrackIndex = -1;

        try {
            extractor = new MediaExtractor();
            FileDescriptor fd = context.getContentResolver()
                    .openFileDescriptor(videoUri, "r").getFileDescriptor();
            extractor.setDataSource(fd);

            final int videoTrackIndex = selectVideoTrack(extractor);
            if (videoTrackIndex < 0) throw new RuntimeException("No video track found");
            extractor.selectTrack(videoTrackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(videoTrackIndex);

            int width = safeGetInt(inputFormat, MediaFormat.KEY_WIDTH, 0);
            int height = safeGetInt(inputFormat, MediaFormat.KEY_HEIGHT, 0);

            // Rotation → render upright
            int rotationDegrees = 0;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, videoUri);
                String rotationStr = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotationStr != null) rotationDegrees = Integer.parseInt(rotationStr);
            } catch (Throwable t) {
                Log.w(TAG, "Rotation read failed: " + t.getMessage());
            } finally {
                try { retriever.release(); } catch (Throwable ignored) {}
            }
            final boolean isPortrait = (rotationDegrees == 90 || rotationDegrees == 270);
            if (isPortrait) { int tmp = width; width = height; height = tmp; }

            long durationUs = safeGetLong(inputFormat, MediaFormat.KEY_DURATION, 0L);
            totalDurationUs = Math.max(durationUs, 1L);
            long firstVideoPTS = -1;

            // Encoder config (preserve source-ish)
            int sourceFps = safeGetInt(inputFormat, MediaFormat.KEY_FRAME_RATE, -1);
            if (sourceFps <= 0) { try { sourceFps = estimateInputFps(context, videoUri); } catch (Throwable ignore) {} }
            if (sourceFps <= 0) sourceFps = 30;

            int sourceBitrate = safeGetInt(inputFormat, MediaFormat.KEY_BIT_RATE, -1);
            if (sourceBitrate <= 0) {
                double bpp = 0.07;
                sourceBitrate = (int) Math.round(width * height * sourceFps * bpp);
                sourceBitrate = Math.max(sourceBitrate, 2 * 1024 * 1024);
            }

            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            encoder = MediaCodec.createEncoderByType("video/avc");

            MediaFormat outputFormat = MediaFormat.createVideoFormat("video/avc", width, height);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, sourceBitrate);
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, sourceFps);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, safeGetInt(inputFormat, MediaFormat.KEY_I_FRAME_INTERVAL, 1));

            final String KEY_COLOR_STANDARD = "color-standard";
            final String KEY_COLOR_TRANSFER = "color-transfer";
            final String KEY_COLOR_RANGE    = "color-range";
            if (inputFormat.containsKey(KEY_COLOR_STANDARD))
                outputFormat.setInteger(KEY_COLOR_STANDARD, inputFormat.getInteger(KEY_COLOR_STANDARD));
            if (inputFormat.containsKey(KEY_COLOR_TRANSFER))
                outputFormat.setInteger(KEY_COLOR_TRANSFER, inputFormat.getInteger(KEY_COLOR_TRANSFER));
            if (inputFormat.containsKey(KEY_COLOR_RANGE))
                outputFormat.setInteger(KEY_COLOR_RANGE, inputFormat.getInteger(KEY_COLOR_RANGE));

            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            // Decoder surface + OES
            int oesTextureId = ShaderUtils.createExternalTexture();
            surfaceTexture = new SurfaceTexture(oesTextureId);
            surfaceTexture.setDefaultBufferSize(width, height);
            decoderSurface = new Surface(surfaceTexture);
            decoder.configure(inputFormat, decoderSurface, null, 0);
            decoder.start();

            // EGL + shader program
            eglHelper = new EGLHelper(inputSurface);
            int program = ShaderUtils.createProgramOrFallback(ShaderUtils.getVertexShaderCode());
            GLES20.glUseProgram(program);
            GLES20.glViewport(0, 0, width, height);

            if (context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                Log.w(TAG, "For best preview while exporting, rotate device to landscape.");
            }

            // LUT (supports asset:/file:/content:)
            int lutSize = 33;
            int lutTextureId = 0;
            final String resolvedLutId = sanitizeLutId(lutId);
            boolean useLut = isLutRequested(resolvedLutId);
            if (useLut) {
                try (InputStream lutStream = LutManager.openLutStream(context, resolvedLutId)) {
                    Pair<Integer, Integer> lutInfo = LUTLoader.loadCubeLUT(lutStream);
                    lutTextureId = lutInfo.first != null ? lutInfo.first : 0;
                    lutSize      = lutInfo.second != null ? lutInfo.second : 33;
                } catch (Throwable e) {
                    Log.e(TAG, "Failed to open LUT", e);
                    useLut = false;
                    mainHandler.post(() -> Toast.makeText(context, "❌ Failed to open LUT", Toast.LENGTH_LONG).show());
                }
            }
            if (lutTextureId == 0) useLut = false;

            // Uniform locations
            int uApplyLUT           = GLES20.glGetUniformLocation(program, "uApplyLUT");
            int uApplyGrade         = GLES20.glGetUniformLocation(program, "uApplyGrade");
            int uLUT                = GLES20.glGetUniformLocation(program, "lutTexture");
            int uTexture            = GLES20.glGetUniformLocation(program, "uTexture");
            int uLUTSize            = GLES20.glGetUniformLocation(program, "uLUTSize");
            int uTintLoc            = GLES20.glGetUniformLocation(program, "uTint");
            int uContrastLoc        = GLES20.glGetUniformLocation(program, "uContrast");
            int uSaturationLoc      = GLES20.glGetUniformLocation(program, "uSaturation");
            int uExposure           = GLES20.glGetUniformLocation(program, "uExposure");
            int uVibrance           = GLES20.glGetUniformLocation(program, "uVibrance");
            int uTempLoc            = GLES20.glGetUniformLocation(program, "uTemp");
            int uTintGM             = GLES20.glGetUniformLocation(program, "uTintGM");
            int uHighlightRoll      = GLES20.glGetUniformLocation(program, "uHighlightRoll");
            int uVignetteStrength   = GLES20.glGetUniformLocation(program, "uVignetteStrength");
            int uVignetteSoftness   = GLES20.glGetUniformLocation(program, "uVignetteSoftness");
            // Optional helpers many vignette shaders use:
            int uResolution         = GLES20.glGetUniformLocation(program, "uResolution"); // vec2
            int uAspect             = GLES20.glGetUniformLocation(program, "uAspect");     // float

            // Static binds
            set1f(uApplyLUT,   useLut ? 1f : 0f);
            set1f(uApplyGrade, 1f);
            set1i(uTexture, 0);
            set1i(uLUT, 1);
            set1f(uLUTSize, (float) lutSize);
            if (useLut) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId);
            }
            // Provide resolution/aspect for vignette falloff if shader wants it
            set2f(uResolution, (float) width, (float) height);
            set1f(uAspect, width / (float) height);

            // Initial uniforms so first frame is correct
            set1f(uTintLoc,          tint);
            set1f(uContrastLoc,      1f + contrastDelta);
            set1f(uSaturationLoc,    1f + saturationDelta);
            set1f(uExposure,         exposure);
            set1f(uVibrance,         vibrance);
            set1f(uTempLoc,          temp);
            set1f(uTintGM,           greenMagenta);
            set1f(uHighlightRoll,    highlightRoll);
            set1f(uVignetteStrength, vignetteStrength);
            set1f(uVignetteSoftness, vignetteSoftness);

            // Muxer (+ audio passthrough)
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { audioTrackIndex = i; audioFormat = format; break; }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean decoderDone = false, encoderDone = false, signaledEOSLocal = false;
            long basePtsUs = -1;

            while (!encoderDone) {
                if (!decoderDone) {
                    int inputBufferId = decoder.dequeueInputBuffer(10000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decoderDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            if (firstVideoPTS < 0) firstVideoPTS = pts;
                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
                int decoderOutputIndex = decoder.dequeueOutputBuffer(decoderInfo, 10000);
                if (decoderOutputIndex >= 0) {
                    decoder.releaseOutputBuffer(decoderOutputIndex, true);
                    surfaceTexture.updateTexImage();

                    // Video frame (unit 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);

                    // Re-bind LUT every frame (driver safety)
                    if (useLut) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId);
                    }

                    // Per-frame grade (includes vignette)
                    set1f(uTintLoc,          tint);
                    set1f(uContrastLoc,      1f + contrastDelta);
                    set1f(uSaturationLoc,    1f + saturationDelta);
                    set1f(uExposure,         exposure);
                    set1f(uVibrance,         vibrance);
                    set1f(uTempLoc,          temp);
                    set1f(uTintGM,           greenMagenta);
                    set1f(uHighlightRoll,    highlightRoll);
                    set1f(uVignetteStrength, vignetteStrength);
                    set1f(uVignetteSoftness, vignetteSoftness);
                    // Keep optional helpers updated (not strictly necessary if static)
                    set2f(uResolution, (float) width, (float) height);
                    set1f(uAspect, width / (float) height);

                    // Draw & stamp PTS
                    eglHelper.drawFrame(program, useLut ? lutTextureId : 0, lutSize,
                            width, height, oesTextureId, rotationDegrees);

                    long ptsUs = decoderInfo.presentationTimeUs;
                    if (basePtsUs < 0) basePtsUs = ptsUs;
                    long encPtsUs = Math.max(0, ptsUs - basePtsUs);
                    eglHelper.setPresentationTimeUs(encPtsUs);

                    eglHelper.swapBuffers();
                    GLES20.glFinish();

                    if (decoderDone && !signaledEOSLocal) {
                        encoder.signalEndOfInputStream();
                        signaledEOSLocal = true;
                    }
                }

                int encoderStatus = encoder.dequeueOutputBuffer(info, 10000);
                if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndexOut = muxer.addTrack(newFormat);
                    if (audioFormat != null) {
                        storedAudioTrackIndex = muxer.addTrack(audioFormat);
                    }
                    muxer.start();
                    muxerStarted = true;
                } else if (encoderStatus >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxerStarted) {
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrackIndexOut, encodedData, info);

                        long progressUs = info.presentationTimeUs;
                        long denom = Math.max(totalDurationUs, 1L);
                        final int percent = (int) Math.min(100, Math.max(0, (progressUs * 100) / denom));
                        mainHandler.post(() -> {
                            if (progressText != null) progressText.setText(percent + "%");
                            if (circularProgressBar != null) circularProgressBar.setProgress(percent);
                        });
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false);
                    frameIndex++;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }

            // Copy original audio bit-for-bit
            if (audioTrackIndex >= 0 && muxerStarted && storedAudioTrackIndex >= 0) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                if (firstVideoPTS > 0)
                    extractor.seekTo(firstVideoPTS, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                ByteBuffer buffer = ByteBuffer.allocate(65536);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();

                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    audioInfo.offset = 0;
                    audioInfo.size = sampleSize;
                    audioInfo.presentationTimeUs = extractor.getSampleTime();
                    int sampleFlags = extractor.getSampleFlags();
                    int bufferFlags = 0;
                    if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                        bufferFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                    audioInfo.flags = bufferFlags;

                    muxer.writeSampleData(storedAudioTrackIndex, buffer, audioInfo);
                    extractor.advance();
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "Export failed", t);
            final String msg = (t.getMessage() != null) ? t.getMessage() : t.toString();
            mainHandler.post(() -> {
                if (progressLabel != null) progressLabel.setText("Failed");
                if (progressText != null) progressText.setText("Error");
                Toast.makeText(context, "Export failed: " + msg, Toast.LENGTH_LONG).show();
            });
            throw new Exception("LUT export failed: " + msg, t);
        } finally {
            // Release in reverse order
            try { if (muxer != null) { try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {} muxer.release(); } } catch (Throwable ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Throwable ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Throwable ignored) {}
            try { if (eglHelper != null) eglHelper.release(); } catch (Throwable ignored) {}
            try { if (decoderSurface != null) decoderSurface.release(); } catch (Throwable ignored) {}
            try { if (surfaceTexture != null) surfaceTexture.release(); } catch (Throwable ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
        }

        // Scan so Gallery sees it
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{outFile.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
        } catch (Throwable scanErr) {
            Log.w(TAG, "MediaScanner failed: " + scanErr.getMessage());
        }

        mainHandler.post(() -> {
            if (progressText != null) progressText.setText("100%");
            if (progressLabel != null) progressLabel.setText("Done");
            if (circularProgressBar != null) circularProgressBar.setVisibility(INVISIBLE);
        });

        videoUri = null;
    }

    // Estimate FPS from timestamps
    private int estimateInputFps(Context ctx, Uri uri) {
        MediaExtractor ex = new MediaExtractor();
        try {
            FileDescriptor fd = ctx.getContentResolver().openFileDescriptor(uri, "r").getFileDescriptor();
            ex.setDataSource(fd);
            int v = selectVideoTrack(ex);
            if (v < 0) return -1;
            ex.selectTrack(v);
            ArrayList<Long> diffs = new ArrayList<>();
            long last = -1;
            int samples = 0;
            while (samples < 150) {
                int sz = ex.readSampleData(ByteBuffer.allocate(1), 0);
                if (sz < 0) break;
                long t = ex.getSampleTime();
                if (t > 0 && last > 0) {
                    long d = t - last;
                    if (d > 0 && d < 200000) diffs.add(d);
                }
                last = t;
                ex.advance();
                samples++;
            }
            if (diffs.isEmpty()) return -1;
            Collections.sort(diffs);
            long median = diffs.get(diffs.size() / 2);
            return (int) Math.max(1, Math.round(1_000_000.0 / median));
        } catch (Throwable ignore) {
            return -1;
        } finally {
            try { ex.release(); } catch (Throwable ignored) {}
        }
    }

    public static void resetState() {
        signaledEOS = false;
        totalDurationUs = 1;
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }
}
