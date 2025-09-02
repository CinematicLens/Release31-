package com.squeezer.app;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class ShaderImageUtils {

    public static String getVertexShader() {
        return
                "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTexCoord;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "    gl_Position = aPosition;\n" +
                        "    vTexCoord = aTexCoord;\n" +
                        "}";
    }

    public static String getFragmentShader() {
        return

                    "precision mediump float;\n" +
                            "varying vec2 vTexCoord;\n" +
                            "uniform sampler2D uTexture;\n" +
                            "uniform sampler2D lutTexture;\n" +
                            "uniform int uApplyLUT;\n" +
                            "uniform float uLUTSize;\n" +
                            "uniform float uContrast;\n" +
                            "uniform float uSaturation;\n" +
                            "uniform float uHueShift;\n" +

                            "vec3 rgb2hsv(vec3 c) {\n" +
                            "    vec4 K = vec4(0., -1./3., 2./3., -1.);\n" +
                            "    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n" +
                            "    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n" +
                            "    float d = q.x - min(q.w, q.y);\n" +
                            "    float e = 1e-10;\n" +
                            "    return vec3(abs(q.z + (q.w - q.y)/(6.*d+e)), d/(q.x+e), q.x);\n" +
                            "}\n" +

                            "vec3 hsv2rgb(vec3 c) {\n" +
                            "    vec4 K = vec4(1., 2./3., 1./3., 3.);\n" +
                            "    vec3 p = abs(fract(c.xxx + K.xyz) * 6. - K.www);\n" +
                            "    return c.z * mix(K.xxx, clamp(p - K.xxx, 0., 1.), c.y);\n" +
                            "}\n" +

                            "vec4 applyLUT(vec3 color) {\n" +
                            "    float size = uLUTSize;\n" +
                            "    float blueIdx = color.b * (size - 1.0);\n" +
                            "    float zSlice = floor(blueIdx);\n" +
                            "    float zLerp = fract(blueIdx);\n" +
                            "    float x = color.r * (size - 1.0);\n" +
                            "    float y = color.g * (size - 1.0);\n" +

                            "    float sliceSize = 1.0 / size;\n" +
                            "    float slicePixelSize = sliceSize / size;\n" +
                            "    float texX1 = (x + zSlice * size) * slicePixelSize;\n" +
                            "    float texX2 = (x + (zSlice + 1.0) * size) * slicePixelSize;\n" +
                            "    float texY = y / (size - 1.0);\n" +

                            "    vec2 safeCoord1 = clamp(vec2(texX1, texY), 0.0, 1.0);\n" +
                            "    vec2 safeCoord2 = clamp(vec2(texX2, texY), 0.0, 1.0);\n" +
                            "    vec4 sample1 = texture2D(lutTexture, safeCoord1);\n" +
                            "    vec4 sample2 = texture2D(lutTexture, safeCoord2);\n" +
                            "    return mix(sample1, sample2, zLerp);\n" +
                            "}\n" +

                            "void main() {\n" +
                            "    vec4 color = texture2D(uTexture, vTexCoord);\n" +
                            "    if (uApplyLUT == 1) {\n" +
                            "        color = applyLUT(color.rgb);\n" +
                            "    }\n" +
                            "    vec3 hsv = rgb2hsv(color.rgb);\n" +
                            "    hsv.x = fract(hsv.x + clamp(uHueShift, 0.0, 0.04));\n" + // Limited hue shift
                            "    color.rgb = hsv2rgb(hsv);\n" +
                            "    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));\n" +
                            "    color.rgb = mix(vec3(gray), color.rgb, uSaturation);\n" +
                            "    color.rgb = ((color.rgb - 0.5) * uContrast) + 0.5;\n" +
                            "    gl_FragColor = color;\n" +
                            "}\n";


    }
    public static int loadTexture(Bitmap bitmap) {
        if (bitmap == null) return 0;

        int[] textureHandles = new int[1];
        GLES20.glGenTextures(1, textureHandles, 0);
        if (textureHandles[0] == 0) {
            throw new RuntimeException("Error generating texture handle.");
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandles[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        return textureHandles[0];
    }

    public static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            throw new RuntimeException("Could not create OpenGL program");
        }

        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        final int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Error linking program: " + error);
        }

        return program;
    }

    private static int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + error);
        }

        return shader;
    }
    public static void drawFullScreenQuad(int program, float[] mvpMatrix) {
        float[] vertices = {
                -1.0f, -1.0f,
                1.0f, -1.0f,
                -1.0f,  1.0f,
                1.0f,  1.0f
        };

        float[] texCoords = {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        };

        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(vertices).position(0);

        FloatBuffer texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texCoordBuffer.put(texCoords).position(0);

        int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        int uMVPMatrix = GLES20.glGetUniformLocation(program, "uMVPMatrix");

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glUniformMatrix4fv(uMVPMatrix, 1, false, mvpMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }


}