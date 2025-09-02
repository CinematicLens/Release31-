package com.squeezer.app;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class ShaderUtils {

    private static final String TAG = "ShaderUtils";

    // --------- Quad cache (no per-frame allocations) ---------
    private static final float[] QUAD_VERTICES = {
            -1f, -1f,   1f, -1f,
            -1f,  1f,   1f,  1f
    };
    private static final float[] QUAD_TEXCOORDS = {
            0f, 1f,   1f, 1f,
            0f, 0f,   1f, 0f
    };
    private static FloatBuffer sVtxBuf, sTexBuf;
    private static int sAttrPos = -1, sAttrTex = -1, sUniMvp = -1;
    private static int sLastProgram = 0;

    // -------------------------------- Core utils --------------------------------

    public static int loadShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            Log.e(TAG, (type == GLES20.GL_VERTEX_SHADER ? "Vertex" : "Fragment")
                    + " compile failed:\n" + log + "\n--- Source ---\n" + src);
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public static int createProgram(String vertexSrc, String fragmentSrc) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        if (vs == 0) return 0;

        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        if (fs == 0) {
            GLES20.glDeleteShader(vs);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            Log.e(TAG, "Program link failed:\n" + log);
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        // shaders can be deleted once linked
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    /** Try LUT shader; if it fails, fall back to OES-only to avoid hard crash. */
    public static int createProgramOrFallback(String vertexSrc) {
        int p = createProgram(vertexSrc, getFragmentShaderWithLUT());
        if (p != 0) return p;

        Log.e(TAG, "Falling back to OES-only fragment shader.");
        return createProgram(vertexSrc, getFragmentShaderOESOnly());
    }

    // -------------------------------- Shaders --------------------------------

    public static String getVertexShaderCode() {
        return ""
                + "attribute vec2 aPosition;\n"
                + "attribute vec2 aTexCoord;\n"
                + "uniform   mat4 uMVPMatrix;\n"
                + "varying   vec2 vTexCoord;\n"
                + "void main(){\n"
                + "  vTexCoord = aTexCoord;\n"
                + "  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);\n"
                + "}\n";
    }

    /** Fallback fragment: shows the raw video frame (no LUT, no grade). */
    public static String getFragmentShaderOESOnly() {
        return ""
                + "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "precision mediump samplerExternalOES;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform samplerExternalOES uTexture;\n"
                + "void main(){ gl_FragColor = texture2D(uTexture, vTexCoord); }\n";
    }

    /** LUT + grade fragment. Flags are floats for better driver compatibility. */
    public static String getFragmentShaderWithLUT() {
        return ""
                + "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "precision mediump sampler2D;\n"
                + "precision mediump samplerExternalOES;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform samplerExternalOES uTexture;\n"
                + "uniform sampler2D  lutTexture;\n"
                + "uniform float      uLUTSize;\n"
                + "uniform float      uApplyLUT;\n"
                + "uniform float      uApplyGrade;\n"
                + "uniform float      uContrast;   // 1.0 neutral\n"
                + "uniform float      uSaturation; // 1.0 neutral\n"
                + "uniform float      uTint;       // add RGB equally\n"
                + "uniform float      uExposure;   // stops, ~[-2, +2]\n"
                + "uniform float      uVibrance;   // >0 boosts low-sat more\n"
                + "uniform float      uTemp;       // blue(-) … warm(+)\n"
                + "uniform float      uTintGM;     // green(+) magenta(-)\n"
                + "uniform float      uHighlightRoll; // soft knee amount\n"
                + "uniform float      uVignetteStrength; // 0..1\n"
                + "uniform float      uVignetteSoftness; // 0..1\n"
                + "\n"
                + "vec3 sampleLUT(vec3 color){\n"
                + "  float size = uLUTSize; float n = size - 1.0;\n"
                + "  vec3 c = clamp(color, 0.0, 1.0) * n;\n"
                + "  float b0 = floor(c.b); float b1 = min(b0+1.0, n); float bf = c.b - b0;\n"
                + "  float x0 = c.r + b0*size; float x1 = c.r + b1*size; float y = c.g;\n"
                + "  vec2 uv0 = vec2((x0+0.5)/(size*size), (y+0.5)/size);\n"
                + "  vec2 uv1 = vec2((x1+0.5)/(size*size), (y+0.5)/size);\n"
                + "  return mix(texture2D(lutTexture, uv0).rgb, texture2D(lutTexture, uv1).rgb, bf);\n"
                + "}\n"
                + "\n"
                + "vec3 applyTempTint(vec3 c){\n"
                + "  c += vec3(uTemp*0.08, 0.0, -uTemp*0.08);       // warm/cool\n"
                + "  c += vec3(-uTintGM*0.06, uTintGM*0.06, 0.0);    // green/magenta\n"
                + "  return c;\n"
                + "}\n"
                + "\n"
                + "vec3 vibrance(vec3 c){\n"
                + "  float l = dot(c, vec3(0.2126,0.7152,0.0722));\n"
                + "  float sat = length(c - vec3(l));\n"
                + "  float k = clamp(uVibrance*(1.0 - sat), -1.0, 1.0);\n"
                + "  return mix(vec3(l), c, 1.0 + k);\n"
                + "}\n"
                + "\n"
                + "vec3 highlightRollOff(vec3 c){\n"
                + "  if (uHighlightRoll <= 0.0) return c;\n"
                + "  vec3 t = smoothstep(0.7, 1.0, c);\n"
                + "  return mix(c, 1.0 - (1.0 - c)*(1.0 - t), uHighlightRoll);\n"
                + "}\n"
                + "\n"
                + "void main(){\n"
                + "  vec2 uv = vTexCoord;\n"
                + "  vec4 src = texture2D(uTexture, uv);\n"
                + "  vec3 c = src.rgb;\n"
                + "  if (uApplyLUT > 0.5) c = sampleLUT(c);\n"
                + "  if (uApplyGrade > 0.5){\n"
                + "    c *= exp2(uExposure);\n"
                + "    c = applyTempTint(c);\n"
                + "    c = vibrance(c);\n"
                + "    c = (c - 0.5) * uContrast + 0.5;\n"
                + "    float l = dot(c, vec3(0.2126,0.7152,0.0722));\n"
                + "    c = mix(vec3(l), c, uSaturation);\n"
                + "    c += vec3(uTint);\n"
                + "    c = highlightRollOff(c);\n"
                + "    // simple radial vignette in NDC-ish tex space\n"
                + "    if (uVignetteStrength > 0.0){\n"
                + "      vec2 d = uv - 0.5; float r = length(d);\n"
                + "      float soft = max(0.0001, uVignetteSoftness);\n"
                + "      float vig = 1.0 - smoothstep(0.5 - soft, 0.5 + soft, r);\n"
                + "      c *= mix(1.0, vig, uVignetteStrength);\n"
                + "    }\n"
                + "  }\n"
                + "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);\n"
                + "}\n";
    }


    // ------------------------------ Texture helpers ------------------------------

    /** Create a 2D LUT texture from float RGB triplets (0..1). Packing: width=size*size, height=size. */
    public static int createLUTTexture(float[] lutData, int size) {
        int width = size * size;
        int height = size;
        int pixelCount = lutData.length / 3;            // expecting size^3

        if (pixelCount != width * height) {
            Log.w(TAG, "LUT data length doesn't match expected size^3; got " + pixelCount
                    + ", expected " + (width * height));
        }

        // RGBA8 buffer
        ByteBuffer buffer = ByteBuffer
                .allocateDirect(pixelCount * 4)
                .order(ByteOrder.nativeOrder());

        for (int i = 0; i < pixelCount; i++) {
            int base = i * 3;
            float r = clamp01(lutData[base]);
            float g = clamp01(lutData[base + 1]);
            float b = clamp01(lutData[base + 2]);
            buffer.put((byte)(r * 255f));
            buffer.put((byte)(g * 255f));
            buffer.put((byte)(b * 255f));
            buffer.put((byte)255);
        }
        buffer.position(0);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);

        Log.d(TAG, "Created LUT texture id=" + tex[0] + " size=" + size + " (" + width + "x" + height + ")");
        return tex[0];
    }

    public static int createExternalTexture() {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return texture[0];
    }

    // ------------------------------ Quad draw helpers ------------------------------

    /** Call after glUseProgram(program). Initializes cached buffers/locations if needed. */
    private static void ensureQuadInit(int program) {
        if (sVtxBuf == null) {
            sVtxBuf = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            sVtxBuf.put(QUAD_VERTICES).position(0);

            sTexBuf = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            sTexBuf.put(QUAD_TEXCOORDS).position(0);
        }
        if (program != sLastProgram || sAttrPos < 0 || sAttrTex < 0) {
            sLastProgram = program;
            sAttrPos = GLES20.glGetAttribLocation(program, "aPosition");
            sAttrTex = GLES20.glGetAttribLocation(program, "aTexCoord");
            sUniMvp  = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        }
    }

    /** Draw a fullscreen quad using the cached buffers — no allocations per frame. */
    public static void drawFullScreenQuad(int program, float[] mvpMatrix) {
        ensureQuadInit(program);

        if (sUniMvp >= 0 && mvpMatrix != null) {
            GLES20.glUniformMatrix4fv(sUniMvp, 1, false, mvpMatrix, 0);
        }

        GLES20.glEnableVertexAttribArray(sAttrPos);
        GLES20.glVertexAttribPointer(sAttrPos, 2, GLES20.GL_FLOAT, false, 0, sVtxBuf);

        GLES20.glEnableVertexAttribArray(sAttrTex);
        GLES20.glVertexAttribPointer(sAttrTex, 2, GLES20.GL_FLOAT, false, 0, sTexBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(sAttrPos);
        GLES20.glDisableVertexAttribArray(sAttrTex);
    }

    // ------------------------------ Misc helpers ------------------------------

    /** Only use this if you really need to push a dynamic float vec2 array as attrib. */
    public static void setFloatArray(int handle, float[] array) {
        FloatBuffer buffer = ByteBuffer
                .allocateDirect(array.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(array).position(0);
        GLES20.glEnableVertexAttribArray(handle);
        GLES20.glVertexAttribPointer(handle, 2, GLES20.GL_FLOAT, false, 0, buffer);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
