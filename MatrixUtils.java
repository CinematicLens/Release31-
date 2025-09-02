package com.squeezer.app;

import android.opengl.GLES20;
import android.opengl.Matrix;

public class MatrixUtils {

    public static float[] getCorrectedMVPMatrix(int viewWidth, int viewHeight,
                                                int videoWidth, int videoHeight,
                                                int videoRotation) {
        float[] mvpMatrix = new float[16];

        // Correct video dimensions for portrait orientation
        boolean isPortrait = (videoRotation == 90 || videoRotation == 270);
        int correctedWidth = videoWidth;
        int correctedHeight = videoHeight;
        if (isPortrait) {
            int temp = correctedWidth;
            correctedWidth = correctedHeight;
            correctedHeight = temp;
        }

        // Aspect ratio scaling
        float viewAspect = (float) viewWidth / viewHeight;
        float videoAspect = (float) correctedWidth / correctedHeight;

        float scaleX = 1f;
        float scaleY = 1f;
        if (videoAspect > viewAspect) {
            scaleY = viewAspect / videoAspect;
        } else {
            scaleX = videoAspect / viewAspect;
        }

        // Start building matrix
        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f);

        // Apply rotation and flip
        if (videoRotation == 90) {
            Matrix.rotateM(mvpMatrix, 0, 270, 0f, 0f, 1f);
            Matrix.scaleM(mvpMatrix, 0, 1f, -1f, 1f);
        } else if (videoRotation == 180 && !isPortrait) {
            Matrix.rotateM(mvpMatrix, 0, 180, 0f, 0f, 1f);
        }

        return mvpMatrix;
    }
}
