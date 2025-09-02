package com.squeezer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.opengl.GLES20;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.IntBuffer;

public class LUTImageProcessor {

    // ------------------------- Public API (legacy) -------------------------
    // Keeps your old signature working, but internally uses the advanced path.
    // 'contrast' and 'saturation' here are ABSOLUTE (neutral = 1.0), and 'hueShift' is centered at 0.0.
    public static void process(Context context,
                               Uri imageUri,
                               @Nullable String lutId,
                               OutputStream out,
                               float contrast /* abs 1.0=neutral */,
                               float saturation /* abs 1.0=neutral */,
                               float hueShift /* 0.0=neutral */) throws Exception {

        float contrastDelta   = contrast   - 1f;  // map absolute -> delta (shader uses 1.0 + delta)
        float saturationDelta = saturation - 1f;

        processAdvanced(
                context,
                imageUri,
                lutId,                 // may be null / "none" / "asset:..." / "file:..."
                out,
                /* tint / hueShift: */ hueShift,
                /* contrastΔ:       */ contrastDelta,
                /* saturationΔ:     */ saturationDelta,
                /* exposure        */ 0f,
                /* vibrance        */ 0f,
                /* temp            */ 0f,
                /* greenMagenta    */ 0f,
                /* highlightRoll   */ 0f,
                /* vignetteStrength*/ 0f,
                /* vignetteSoftness*/ 0f
        );
    }

    // ------------------------- Public API (advanced) -------------------------
    // Matches the video pipeline semantics. Use this whenever available.
    // tint/hueShift:  0.0 neutral
    // contrastDelta:  0.0 neutral  (shader uses 1.0 + contrastDelta)
    // saturationDelta:0.0 neutral  (shader uses 1.0 + saturationDelta)
    public static void processAdvanced(Context context,
                                       Uri imageUri,
                                       @Nullable String lutId,
                                       OutputStream out,
                                       float tint,
                                       float contrastDelta,
                                       float saturationDelta,
                                       float exposure,
                                       float vibrance,
                                       float temp,
                                       float greenMagenta,
                                       float highlightRoll,
                                       float vignetteStrength,
                                       float vignetteSoftness) throws Exception {

        Bitmap rawBitmap = null;
        Bitmap inputBitmap = null;
        SurfaceTexture surfaceTexture = null;
        EGLImageHelper egl = null;

        int program = 0;
        int srcTexId = 0;
        int lutTexId = 0;

        try {
            // 1) Load + EXIF rotate
            rawBitmap = loadFullQualityBitmap(context, imageUri);
            inputBitmap = applyExifRotation(context, imageUri, rawBitmap);
            final int width = inputBitmap.getWidth();
            final int height = inputBitmap.getHeight();

            // 2) Offscreen EGL
            surfaceTexture = new SurfaceTexture(0);
            egl = new EGLImageHelper();
            egl.initEGL(width, height, surfaceTexture);

            // 3) Upload image
            srcTexId = ShaderImageUtils.loadTexture(inputBitmap);

            // 4) Build shader (same one you use for preview)
            program = ShaderImageUtils.createProgram(
                    ShaderImageUtils.getVertexShader(),
                    ShaderImageUtils.getFragmentShader()
            );
            GLES20.glUseProgram(program);
            GLES20.glViewport(0, 0, width, height);

            // 5) Bind source (unit 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId);
            set1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);

            // 6) Optional LUT bind (unit 1)
            boolean wantLut = hasLut(lutId);
            int uApplyLUT   = GLES20.glGetUniformLocation(program, "uApplyLUT");
            int uLUTSize    = GLES20.glGetUniformLocation(program, "uLUTSize");
            int uLUTSampler = GLES20.glGetUniformLocation(program, "lutTexture");

            if (wantLut && uLUTSampler >= 0 && uApplyLUT >= 0) {
                InputStream is = null;
                try {
                    is = openLutStream(context, lutId);
                    if (is != null) {
                        android.util.Pair<Integer, Integer> p = LUTLoader.loadCubeLUT(is);
                        lutTexId = (p.first != null) ? p.first : 0;
                        int lutSize = (p.second != null) ? p.second : 33;
                        if (lutTexId != 0) {
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId);
                            set1i(uLUTSampler, 1);
                            set1f(uLUTSize, (float) lutSize);
                            set1f(uApplyLUT, 1f);
                        } else {
                            set1f(uApplyLUT, 0f);
                        }
                    } else {
                        set1f(uApplyLUT, 0f);
                    }
                } finally {
                    if (is != null) try { is.close(); } catch (Throwable ignored) {}
                }
            } else {
                set1f(uApplyLUT, 0f);
            }

            // 7) Grade uniforms
            // Some shaders also have uApplyGrade; set it if present.
            set1f(GLES20.glGetUniformLocation(program, "uApplyGrade"), 1f);
            set1f(GLES20.glGetUniformLocation(program, "uContrast"),   1f + contrastDelta); // neutral = 1.0
            set1f(GLES20.glGetUniformLocation(program, "uSaturation"), 1f + saturationDelta); // neutral = 1.0
            // Your image shader calls it uHueShift; if you also support uTint, set both safely.
            set1f(GLES20.glGetUniformLocation(program, "uHueShift"),   tint);
            set1f(GLES20.glGetUniformLocation(program, "uTint"),       tint);

            // Advanced (safe if not present in shader)
            set1f(GLES20.glGetUniformLocation(program, "uExposure"),         exposure);
            set1f(GLES20.glGetUniformLocation(program, "uVibrance"),         vibrance);
            set1f(GLES20.glGetUniformLocation(program, "uTemp"),             temp);
            set1f(GLES20.glGetUniformLocation(program, "uTintGM"),           greenMagenta);
            set1f(GLES20.glGetUniformLocation(program, "uHighlightRoll"),    highlightRoll);
            set1f(GLES20.glGetUniformLocation(program, "uVignetteStrength"), vignetteStrength);
            set1f(GLES20.glGetUniformLocation(program, "uVignetteSoftness"), vignetteSoftness);

            // 8) MVP (flip + fit)
            float[] mvp = new float[16];
            android.opengl.Matrix.setIdentityM(mvp, 0);
            // GL origin flip so readback is upright
            android.opengl.Matrix.scaleM(mvp, 0, 1f, -1f, 1f);

            float imageAspect = (float) width / (float) height;
            float sx = 1f, sy = 1f;
            if (imageAspect > 1f) sy = 1f / imageAspect; else sx = imageAspect;
            android.opengl.Matrix.scaleM(mvp, 0, sx, sy, 1f);
            int uMVP = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            if (uMVP >= 0) GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);

            // 9) Draw
            ShaderImageUtils.drawFullScreenQuad(program, mvp);
            GLES20.glFinish();

            // 10) Readback & save
            IntBuffer buf = IntBuffer.allocate(width * height);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);

            Bitmap outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            outBmp.copyPixelsFromBuffer(buf);
            outBmp = flipBitmapVertically(outBmp); // match your previous behavior

            outBmp.compress(Bitmap.CompressFormat.JPEG, 100, out);

            // cleanup local bitmap
            if (!outBmp.isRecycled()) outBmp.recycle();
            buf.clear();

        } finally {
            // GL cleanup
            if (program != 0) {
                try { GLES20.glDeleteProgram(program); } catch (Throwable ignored) {}
            }
            if (srcTexId != 0) {
                try { int[] t = {srcTexId}; GLES20.glDeleteTextures(1, t, 0); } catch (Throwable ignored) {}
            }
            if (lutTexId != 0) {
                try { int[] t = {lutTexId}; GLES20.glDeleteTextures(1, t, 0); } catch (Throwable ignored) {}
            }
            if (egl != null) {
                try { egl.release(); } catch (Throwable ignored) {}
            }
            if (surfaceTexture != null) {
                try { surfaceTexture.release(); } catch (Throwable ignored) {}
            }

            // Bitmaps
            if (inputBitmap != null && !inputBitmap.isRecycled()) {
                inputBitmap.recycle();
            }
            if (rawBitmap != null && !rawBitmap.isRecycled()) {
                rawBitmap.recycle();
            }
        }
    }

    // ------------------------- Preview (updated LUT support) -------------------------
    public static Bitmap getPreviewBitmap(Context context,
                                          Uri imageUri,
                                          @Nullable String lutId,
                                          float contrastAbs,
                                          float saturationAbs,
                                          float hueShift) {
        SurfaceTexture surfaceTexture = null;
        EGLImageHelper egl = null;
        int program = 0;
        int textureId = 0;
        int lutTexId = 0;

        try {
            // 1) Load source bitmap + correct EXIF
            Bitmap rawBitmap = loadFullQualityBitmap(context, imageUri);
            Bitmap inputBitmap = applyExifRotation(context, imageUri, rawBitmap);
            int width = inputBitmap.getWidth();
            int height = inputBitmap.getHeight();

            // 2) Offscreen EGL target
            surfaceTexture = new SurfaceTexture(0);
            egl = new EGLImageHelper();
            egl.initEGL(width, height, surfaceTexture);

            // 3) Upload image as 2D texture
            textureId = ShaderImageUtils.loadTexture(inputBitmap);

            // 4) Build shader program (supports LUT + grade uniforms)
            program = ShaderImageUtils.createProgram(
                    ShaderImageUtils.getVertexShader(),
                    ShaderImageUtils.getFragmentShader()
            );
            GLES20.glUseProgram(program);
            GLES20.glViewport(0, 0, width, height);

            // 5) Bind source texture (unit 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            set1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);

            // 6) Optional LUT (supports null / "none" / asset:/file:/plain)
            boolean wantLut = hasLut(lutId);
            int uApplyLUTLoc = GLES20.glGetUniformLocation(program, "uApplyLUT");
            int uLUTSizeLoc  = GLES20.glGetUniformLocation(program, "uLUTSize");
            int uLUTSampler  = GLES20.glGetUniformLocation(program, "lutTexture");

            if (wantLut && uLUTSampler >= 0 && uApplyLUTLoc >= 0) {
                InputStream is = null;
                try {
                    is = openLutStream(context, lutId);
                    if (is != null) {
                        android.util.Pair<Integer, Integer> p = LUTLoader.loadCubeLUT(is);
                        lutTexId = (p.first != null) ? p.first : 0;
                        int lutSize = (p.second != null) ? p.second : 33;
                        if (lutTexId != 0) {
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId);
                            set1i(uLUTSampler, 1);
                            set1f(uLUTSizeLoc, (float) lutSize);
                            set1f(uApplyLUTLoc, 1f);
                        } else {
                            set1f(uApplyLUTLoc, 0f);
                        }
                    } else {
                        set1f(uApplyLUTLoc, 0f);
                    }
                } finally {
                    if (is != null) try { is.close(); } catch (Throwable ignored) {}
                }
            } else {
                set1f(uApplyLUTLoc, 0f);
            }

            // 7) Grade uniforms (preview uses ABSOLUTE values here)
            set1f(GLES20.glGetUniformLocation(program, "uContrast"),   contrastAbs);    // 1.0 neutral
            set1f(GLES20.glGetUniformLocation(program, "uSaturation"), saturationAbs);  // 1.0 neutral
            set1f(GLES20.glGetUniformLocation(program, "uHueShift"),   hueShift);       // 0.0 neutral

            // 8) MVP (flip + fit)
            float[] mvpMatrix = new float[16];
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0);
            android.opengl.Matrix.scaleM(mvpMatrix, 0, 1f, -1f, 1f); // Flip Y

            float imageAspect = (float) width / (float) height;
            float scaleX = 1f, scaleY = 1f;
            if (imageAspect > 1f) { scaleY = 1f / imageAspect; } else { scaleX = imageAspect; }
            android.opengl.Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f);

            int uMVPLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            if (uMVPLoc >= 0) GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0);

            // 9) Draw and flush
            ShaderImageUtils.drawFullScreenQuad(program, mvpMatrix);
            GLES20.glFinish();

            // 10) Readback
            IntBuffer pixelBuffer = IntBuffer.allocate(width * height);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);

            Bitmap outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            outputBitmap.copyPixelsFromBuffer(pixelBuffer);

            // Keep CPU flip to match previous behavior
            outputBitmap = flipBitmapVertically(outputBitmap);

            // Recycle inputs
            if (!inputBitmap.isRecycled()) inputBitmap.recycle();
            if (!rawBitmap.isRecycled()) rawBitmap.recycle();

            return outputBitmap;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            // Cleanup GL objects
            if (program != 0)   try { GLES20.glDeleteProgram(program); } catch (Throwable ignored) {}
            if (textureId != 0) try { int[] t = {textureId}; GLES20.glDeleteTextures(1, t, 0); } catch (Throwable ignored) {}
            if (lutTexId != 0)  try { int[] t = {lutTexId};  GLES20.glDeleteTextures(1, t, 0); } catch (Throwable ignored) {}
            if (egl != null)    try { egl.release(); } catch (Throwable ignored) {}
            if (surfaceTexture != null) try { surfaceTexture.release(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------- Helpers -------------------------
    private static boolean hasLut(@Nullable String lutId) {
        if (lutId == null) return false;
        String s = lutId.trim();
        if (s.isEmpty()) return false;
        return !"none".equalsIgnoreCase(s);
    }

    // Accepts: null, "none", "asset:Free/Foo.cube", "file:/abs/path.cube", "Free/Foo.cube"
    private static @Nullable InputStream openLutStream(Context ctx, @Nullable String lutId) {
        if (!hasLut(lutId)) return null;
        String s = lutId.trim();

        try {
            if (s.startsWith("asset:")) {
                String rel = s.substring("asset:".length());
                return ctx.getAssets().open("luts/" + rel);
            }
            if (s.startsWith("file:")) {
                String path = s.substring("file:".length());
                File f = new File(path);
                if (f.exists()) return new FileInputStream(f);
                return null;
            }
            // Plain name → try assets first, then file path
            try {
                return ctx.getAssets().open("luts/" + s);
            } catch (Throwable ignore) {
                File f = new File(s);
                if (f.exists()) return new FileInputStream(f);
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void set1f(int loc, float v) { if (loc >= 0) GLES20.glUniform1f(loc, v); }
    private static void set1i(int loc, int v)   { if (loc >= 0) GLES20.glUniform1i(loc, v); }

    private static Bitmap flipBitmapVertically(Bitmap src) {
        Matrix matrix = new Matrix();
        matrix.preScale(1f, -1f);
        Bitmap flipped = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        if (!src.isRecycled()) src.recycle();
        return flipped;
    }

    private static Bitmap applyExifRotation(Context context, Uri imageUri, Bitmap bitmap) throws Exception {
        InputStream exifInput = context.getContentResolver().openInputStream(imageUri);
        ExifInterface exif = new ExifInterface(exifInput);
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (exifInput != null) exifInput.close();

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:  matrix.postRotate(90);  break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            default: break;
        }
        if (matrix.isIdentity()) return bitmap;
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (!bitmap.isRecycled()) bitmap.recycle();
        return rotated;
    }

    private static Bitmap loadFullQualityBitmap(Context context, Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IllegalStateException("Cannot open image input stream");
        Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
        input.close();
        if (bitmap == null) throw new IllegalStateException("Failed to decode bitmap");
        return bitmap;
    }

    public static void resetState() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
        GLES20.glFlush();
        GLES20.glFinish();
        System.gc();
    }
}
