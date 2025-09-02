
package com.squeezer.app;
import static android.view.View.INVISIBLE;

import android.content.ContentValues;
import android.content.Context;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.*;
import android.net.Uri;
import android.opengl.*;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.*;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public class SimulatorActivity extends Activity {
    private static final String TAG = "Squeezer";
    private static final int REQUEST_CODE_PICK_VIDEO = 123;
    private Uri sharedVideoUri = null;
    private ProgressBar progressBar;
    private TextView progressText;
    private ProgressBar circularProgressBar;
    private float selectedFactor = 1.0f;  // Default is no squeeze
    private TextView progressLabel;
    private final List<Button> allFactorButtons = new ArrayList<>();
    private Button pickFileButton;
    private Button squeezeButton;

    private void pickVideoFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        startActivityForResult(Intent.createChooser(intent, "Select a Video"), REQUEST_CODE_PICK_VIDEO);
    }

    private boolean isValidVideoUri(Context context, Uri uri) {
        try {
            String type = context.getContentResolver().getType(uri);
            return type != null && type.startsWith("video/");
        } catch (Exception e) {
            return false;
        }
    }
    private int getRotationDegrees(Context context, Uri videoUri) {
        int rotation = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            String rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotationStr != null) {
                rotation = Integer.parseInt(rotationStr);
            }
        } catch (Exception e) {
            Log.e("Rotation", "❌ Failed to get video rotation: " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return rotation;
    }

    private String getFragmentShaderCode() {
        return "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}";
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        }
        if (!hasPermissions()) {
            requestPermissionsSafe();
            return;
        }

        renderSelectionUI();

        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null && intent.getType().startsWith("video/")) {
            handleSharedVideo(intent);
        }
    }
    private String getSqueezeShaderCode(float stretchFactor, boolean isPortrait) {
        return
                "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTexCoord;\n" +
                        "uniform mat4 uMVPMatrix;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "    gl_Position = uMVPMatrix * aPosition;\n" +  // ✅ Matrix applied
                        "    vTexCoord = aTexCoord;\n" +
                        "}";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null && intent.getType().startsWith("video/")) {
            handleSharedVideo(intent);
        }
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ needs separate permissions for images and videos
            boolean hasImages = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean hasVideo = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            return hasImages && hasVideo;
        } else {
            // For Android 12 and below
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissionsSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                    new String[] {
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO
                    },
                    101
            );
        } else {
            requestPermissions(
                    new String[] {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    101
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (!hasPermissions()) {
            Toast.makeText(this, "Permission required", Toast.LENGTH_LONG).show();
            finish();
        }
    }
    private void addFactorButton(LinearLayout container, String label, float factorValue) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16f);
        button.setAllCaps(false);

        // Equal size buttons using weight
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); // Equal width with weight
        params.setMargins(20, 20, 20, 20);
        button.setLayoutParams(params);

        // Initial unselected style
        GradientDrawable unselected = new GradientDrawable();
        unselected.setCornerRadius(30f);
        unselected.setColor(Color.LTGRAY);
        button.setBackground(unselected);
        button.setTextColor(Color.BLACK);
        button.setPadding(30, 20, 30, 20);

        allFactorButtons.add(button);
        container.addView(button);

        button.setOnClickListener(v -> {
            selectedFactor = factorValue;

            // Reset styles for all buttons
            for (Button b : allFactorButtons) {
                GradientDrawable reset = new GradientDrawable();
                reset.setCornerRadius(30f);
                reset.setColor(Color.LTGRAY);
                b.setBackground(reset);
                b.setTextColor(Color.BLACK);
            }

            // Apply selected style
            GradientDrawable selectedDrawable = new GradientDrawable();
            selectedDrawable.setCornerRadius(30f);
            selectedDrawable.setColor(Color.parseColor("#4CAF50")); // Green
            button.setBackground(selectedDrawable);
            button.setTextColor(Color.WHITE);
        });
    }


    private void renderSelectionUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 150, 40, 80);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        layout.setBackgroundColor(Color.parseColor("#263238")); // Dark blue-gray


        // Heading
        TextView heading = new TextView(this);
        heading.setText("🎞 Anamorphic V Simulation");
        heading.setTextSize(24f);
        heading.setTextColor(Color.WHITE);
        heading.setPadding(0, 0, 0, 40);
        layout.addView(heading);

        // Label
        TextView factorLabel = new TextView(this);
        factorLabel.setText("Select Vertical Expansion Ratio:");
        factorLabel.setTextSize(16F);
        factorLabel.setTextColor(Color.LTGRAY);
        factorLabel.setPadding(0, 0, 0, 10);
        layout.addView(factorLabel);

        // Ratio radio buttons
        LinearLayout factorTabLayout = new LinearLayout(this);
        factorTabLayout.setOrientation(LinearLayout.HORIZONTAL);
        factorTabLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        factorTabLayout.setPadding(0, 10, 0, 30);
        layout.addView(factorTabLayout);

        addFactorButton(factorTabLayout, "1.0x", 1.0f);
        addFactorButton(factorTabLayout, "1.33x", 1.33f);
        addFactorButton(factorTabLayout, "1.55x", 1.55f);
        addFactorButton(factorTabLayout, "2.0x", 2.0f);


        pickFileButton = new Button(this);
        pickFileButton.setText("Load Video from Gallery");
        pickFileButton.setTextSize(20f);
        pickFileButton.setPadding(0, 0, 0, 40);
        styleSoftButton(pickFileButton, "#BDBDBD");
        pickFileButton.setTextColor(Color.BLACK);


        pickFileButton.setOnClickListener(v -> pickVideoFile());
        layout.addView(pickFileButton);





        // Progress label
        progressLabel = new TextView(this);
        progressLabel.setText("\nProgress...");
        progressLabel.setTextSize(16f);
        progressLabel.setTextColor(Color.WHITE);
        layout.addView(progressLabel);
        progressLabel.setVisibility(INVISIBLE);
        // Circular Progress Bar
        circularProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        circularProgressBar.setIndeterminate(false);
        circularProgressBar.setMax(100);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(200, 200);
        circleParams.gravity = Gravity.CENTER;
        circularProgressBar.setLayoutParams(circleParams);
        circularProgressBar.setVisibility(View.GONE);
        layout.addView(circularProgressBar);

        // Progress Text
        progressText = new TextView(this);
        progressText.setTextSize(18f);
        progressText.setTextColor(Color.WHITE);
        progressText.setGravity(Gravity.CENTER);
        progressText.setVisibility(View.GONE);
        layout.addView(progressText);


        squeezeButton = new Button(this);
        squeezeButton.setText("Squeeze");
        styleSoftButton(squeezeButton, "#607D8B");
        squeezeButton.setTextColor(Color.WHITE);
        squeezeButton.setOnClickListener(v -> startSqueezeProcess());
        layout.addView(squeezeButton);
        // Back Button
        Button backBtn = new Button(this);
        backBtn.setText("Back to Mode Selection");
        styleSoftButton(backBtn, "#BDBDBD"); // light gray
        backBtn.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        backParams.setMargins(0, 60, 0, 20);
        backBtn.setLayoutParams(backParams);
        backBtn.setOnClickListener(v -> {
            sharedVideoUri = null;
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
        layout.addView(backBtn);

        // ScrollView wrapping
        ScrollView scrollView = new ScrollView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                v.setPadding(0, top, 0, 0);
                return insets.consumeSystemWindowInsets();
            });
        } else {
            int statusBarHeight = getResources().getIdentifier("status_bar_height", "dimen", "android") > 0
                    ? getResources().getDimensionPixelSize(getResources().getIdentifier("status_bar_height", "dimen", "android"))
                    : (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
            scrollView.setPadding(0, statusBarHeight, 0, 0);
        }

        scrollView.addView(layout);
        setContentView(scrollView);
    }


    private void startSqueezeProcess() {
        if (sharedVideoUri == null) {
            Toast.makeText(this, "Please load a video first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedFactor <= 1.0f) {
            Toast.makeText(this, "Please select a valid squeeze ratio.", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> squeezeVideo(sharedVideoUri, selectedFactor)).start();
       // Toast.makeText(this, "Starting squeeze with factor " + selectedFactor, Toast.LENGTH_SHORT).show();
    }


    private void styleSoftButton(Button button, String hexColor) {
        button.setTextColor(Color.WHITE);
        button.setTextSize(16f);
        button.setAllCaps(false);
        button.setPadding(30, 30, 30, 30);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(40f); // Very soft square corners
        button.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 30, 0, 0);
        button.setLayoutParams(params);
    }

    private void handleSharedVideo(Intent intent) {
        Uri mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (mediaUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        if (mediaUri != null) {
            sharedVideoUri = mediaUri;
          //  Toast.makeText(this, "Video received. Now select a squeeze option.", Toast.LENGTH_LONG).show();
            renderSelectionUI();
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            if (videoUri != null) {
                // 👇 Use this URI for processing or preview
                sharedVideoUri=videoUri;
            }
        }
    }
    private void squeezeVideo(Uri videoUri, float factor) {
      // runOnUiThread(() -> Toast.makeText(this, "Squeezing started...", Toast.LENGTH_SHORT).show());

        try {
            ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(videoUri, "r");
            if (fd == null) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to open video file.", Toast.LENGTH_LONG).show());
                return;
            }

            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(fd.getFileDescriptor());
            fd.close();

            int videoTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    inputFormat = format;
                    break;
                }
            }

            if (videoTrack == -1 || inputFormat == null) {
                runOnUiThread(() -> Toast.makeText(this, "No video track found", Toast.LENGTH_LONG).show());
                return;
            }

            int rotationDegrees = getRotationDegrees(this ,videoUri);
            boolean rotate90 = (rotationDegrees == 90 || rotationDegrees == 270);

            // ⚠️ Reverse logic: squeeze = divide width factor
            float squeezeFactor = factor;  // 0.75f = squeeze horizontally by 1.33x

            String vertexShader = getSqueezeShaderCode(squeezeFactor, rotate90);
            String fragmentShader = getFragmentShaderCode();

            Log.d(TAG, "Selected vertex shader for squeeze: " + vertexShader);

            renderAndMuxSqeeze(extractor, inputFormat, videoTrack, squeezeFactor, vertexShader, fragmentShader,videoUri);

        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }
    private String getSoftwareH264EncoderName() {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) continue;
            String name = codecInfo.getName();
            if (name.toLowerCase().contains("omx.google.h264")) {
                return name;
            }
        }
        return null;
    }
    private void renderAndMuxSqeeze(MediaExtractor extractor, MediaFormat inputFormat, int videoTrack, float factor, String vertexShaderCode, String fragmentShaderCode,  Uri videoUri) {
        // runOnUiThread(() -> Toast.makeText(this, "🔧 Squeezing video...", Toast.LENGTH_SHORT).show());
        runOnUiThread(() -> {
            circularProgressBar.setVisibility(View.VISIBLE);
            progressText.setVisibility(View.VISIBLE);
            circularProgressBar.setProgress(0);
            progressText.setText("0%");
        });
        try {
            int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int rotationDegrees = getRotationDegrees(this, videoUri);
            boolean rotate90 = (rotationDegrees == 90 || rotationDegrees == 270);

            int outputWidth;
            int outputHeight;

            if (rotate90) {
                // Portrait — apply vertical squeeze (i.e., stretch height)
                outputWidth = height;
                outputHeight = (int) (width * factor);
            } else {
                // Landscape — apply vertical squeeze (i.e., stretch height)
                outputWidth = width;
                outputHeight = (int) (height * factor);
            }

            extractor.selectTrack(videoTrack);

            long totalDurationUs = extractor.getCachedDuration();
            if (totalDurationUs <= 0 && extractor.getTrackFormat(videoTrack).containsKey(MediaFormat.KEY_DURATION)) {
                totalDurationUs = extractor.getTrackFormat(videoTrack).getLong(MediaFormat.KEY_DURATION);
            }
            if (totalDurationUs <= 0) {
                totalDurationUs = 1_000_000;
            }

             videoUri = null;
            ParcelFileDescriptor pfd = null;
            String outputPath;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {  // API 29+
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, "squeezed_video_" + System.currentTimeMillis() + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Squeezed");

                videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (videoUri == null) {
                    throw new IOException("❌ Failed to insert video in MediaStore");
                }

                pfd = getContentResolver().openFileDescriptor(videoUri, "w");
                if (pfd == null) {
                    throw new IOException("❌ Cannot create output file");
                }

                outputPath = "/proc/self/fd/" + pfd.getFd();

            } else {
                // ✅ Legacy fallback for Android 9 and below (API < 29)
                File legacyDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Squeezed");
                if (!legacyDir.exists()) legacyDir.mkdirs();

                File outFile = new File(legacyDir, "squeezed_video_" + System.currentTimeMillis() + ".mp4");
                outputPath = outFile.getAbsolutePath();
            }

            MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));

             // runOnUiThread(() -> Toast.makeText(this, "🔧 Configuring encoder...", Toast.LENGTH_SHORT).show());

            MediaCodec encoder;
            MediaFormat outputFormat = MediaFormat.createVideoFormat("video/avc", outputWidth, outputHeight);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, outputWidth * outputHeight * 6);
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            try {
                encoder = MediaCodec.createEncoderByType("video/avc");
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
               //   runOnUiThread(() -> Toast.makeText(this, "✅ Hardware encoder configured", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                //unOnUiThread(() -> Toast.makeText(this, "⚠️ Hardware encoder failed, trying software fallback", Toast.LENGTH_LONG).show());
                Log.w(TAG, "⚠️ Hardware encoder error", e);

                String softwareEncoderName = getSoftwareH264EncoderName();
                if (softwareEncoderName == null) {
                    throw new IOException("❌ Software H.264 encoder not found");
                }
                encoder = MediaCodec.createByCodecName(softwareEncoderName);

                outputFormat.setInteger(MediaFormat.KEY_WIDTH, 1280);
                outputFormat.setInteger(MediaFormat.KEY_HEIGHT, 720);
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);  // 8 Mbps
                outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                 runOnUiThread(() -> Toast.makeText(this, "✅ Software encoder (OMX.google) configured", Toast.LENGTH_SHORT).show());
            }

            Surface inputSurface = encoder.createInputSurface();
            encoder.start();
            //runOnUiThread(() -> Toast.makeText(this, "🚀 Encoder started", Toast.LENGTH_SHORT).show());
            EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            EGL14.eglInitialize(eglDisplay, null, 0, null, 0);
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, new int[]{
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            }, 0, configs, 0, 1, numConfigs, 0);

            EGLConfig eglConfig = configs[0];
            EGLContext eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            EGLSurface eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, new int[]{EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);




            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            SurfaceTexture surfaceTexture = new SurfaceTexture(textureId);
            Surface decoderSurface = new Surface(surfaceTexture);
            decoder.configure(inputFormat, decoderSurface, null, 0);
            decoder.start();

            int vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(vertexShader, vertexShaderCode);
            GLES20.glCompileShader(vertexShader);
            int fragShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(fragShader, fragmentShaderCode);
            GLES20.glCompileShader(fragShader);
            int shaderProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(shaderProgram, vertexShader);
            GLES20.glAttachShader(shaderProgram, fragShader);
            GLES20.glLinkProgram(shaderProgram);
            GLES20.glUseProgram(shaderProgram);
            int stretchFactorLoc = GLES20.glGetUniformLocation(shaderProgram, "stretchFactor");
            GLES20.glUniform1f(stretchFactorLoc, selectedFactor);
            int aPosition = GLES20.glGetAttribLocation(shaderProgram, "aPosition");
            int aTexCoord = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord");
            int uTexture = GLES20.glGetUniformLocation(shaderProgram, "uTexture");





            float[] squareCoords = {
                    -1.0f,  1.0f,
                    -1.0f, -1.0f,
                    1.0f,  1.0f,
                    1.0f, -1.0f
            };
            float[] texCoords = {
                    0.0f, 0.0f,
                    0.0f, 1.0f,
                    1.0f, 0.0f,
                    1.0f, 1.0f
            };

            FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(squareCoords.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(squareCoords).position(0);

            FloatBuffer texBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            texBuffer.put(texCoords).position(0);
           // runOnUiThread(() -> Toast.makeText(this, "🚀 Codec Buffer", Toast.LENGTH_SHORT).show());
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            boolean muxerStarted = false;
            int trackIndex = -1;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {

                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }
             //   runOnUiThread(() -> Toast.makeText(this, "🚀 Codec Buffer after", Toast.LENGTH_SHORT).show());
                int outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outIndex >= 0) {
                    surfaceTexture.updateTexImage();
                    GLES20.glViewport(0, 0, outputWidth, outputHeight);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
                    GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
                    GLES20.glEnableVertexAttribArray(aPosition);
                    GLES20.glEnableVertexAttribArray(aTexCoord);
                    GLES20.glUniform1i(uTexture, 0);




                    Log.d("glUniform1i", "✔ aPosition = " + aPosition + ", aTexCoord = " + aTexCoord + ", uTexture = " + uTexture);

// MVP matrix setup for simulator mode (vertical stretch)
                    float[] mvpMatrix = new float[16];
                    android.opengl.Matrix.setIdentityM(mvpMatrix, 0);
                    Log.d("glUniform1i", "📐 Set identity matrix");

                    if (rotationDegrees == 90) {
                        android.opengl.Matrix.rotateM(mvpMatrix, 0, 90, 0f, 0f, 1f);
                        android.opengl.Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f); // Horizontal flip to correct portrait
                        Log.d("glUniform1i", "↪️ Applied 90° rotation and horizontal flip for portrait");
                    } else if (rotationDegrees == 270) {
                        android.opengl.Matrix.rotateM(mvpMatrix, 0, 270, 0f, 0f, 1f);
                        Log.d("glUniform1i", "↪️ Applied 270° rotation");
                    } else {
                        Log.d("glUniform1i", "➡️ No rotation applied (landscape assumed)");
                    }

                    int mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix");
                    if (mvpMatrixHandle == -1) {
                        Log.e("glUniform1i", "❌ Could not find uMVPMatrix in shader!");
                    } else {
                        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
                        Log.d("glUniform1i", "✅ Set uMVPMatrix uniform for simulator mode");
                    }







                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, bufferInfo.presentationTimeUs * 1000);
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                    decoder.releaseOutputBuffer(outIndex, true);


                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream();
                    }
                }

                int encOut = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new RuntimeException("Format changed twice");
                    MediaFormat newFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(newFormat);
                    // Add audio track if available
                    int audioTrackIndex = -1;
                    MediaFormat audioFormat = null;
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat f = extractor.getTrackFormat(i);
                        String mime = f.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) {
                            audioTrackIndex = i;
                            audioFormat = f;
                            break;
                        }
                    }

                    int muxerAudioTrack = -1;
                    if (audioTrackIndex >= 0 && audioFormat != null) {
                        muxerAudioTrack = muxer.addTrack(audioFormat);
                    }
                    muxer.start();
                    muxerStarted = true;
                    if (muxerAudioTrack >= 0) {
                        MediaExtractor audioExtractor = new MediaExtractor();
                        ParcelFileDescriptor afd = getContentResolver().openFileDescriptor(sharedVideoUri, "r");
                        audioExtractor.setDataSource(afd.getFileDescriptor());
                        afd.close();
                     //   runOnUiThread(() -> Toast.makeText(this, "🚀 Audio extractor", Toast.LENGTH_SHORT).show());
                        audioExtractor.selectTrack(audioTrackIndex);
                        ByteBuffer buffer = ByteBuffer.allocate(65536);
                        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();

                        while (true) {
                            int sampleSize = audioExtractor.readSampleData(buffer, 0);
                            if (sampleSize < 0) break;

                            audioInfo.offset = 0;
                            audioInfo.size = sampleSize;
                            audioInfo.presentationTimeUs = audioExtractor.getSampleTime();
                            int sampleFlags = audioExtractor.getSampleFlags();
                            int bufferFlags = 0;
                            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                bufferFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                            }
                            bufferInfo.flags = bufferFlags;
                            muxer.writeSampleData(muxerAudioTrack, buffer, audioInfo);
                            audioExtractor.advance();
                        }

                        audioExtractor.release();
                    }
                } else if (encOut >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encOut);
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                        long progressUs = bufferInfo.presentationTimeUs;
                        int percent = (int) ((progressUs * 100) / totalDurationUs);
                        runOnUiThread(() -> {
                            if (circularProgressBar != null) {
                                progressText.setText(percent + "%");
                            }
                            if (progressText != null) {
                                progressLabel.setText("\n Frame Progress...");
                                progressText.setText(percent + "%");
                            }
                        });
                    }
                    encoder.releaseOutputBuffer(encOut, false);
                //    runOnUiThread(() -> Toast.makeText(this, "✅ Squeeze complete check folder DCIM/Squeezed !", Toast.LENGTH_LONG).show());
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            decoder.stop(); decoder.release();
            encoder.stop(); encoder.release();
            muxer.stop(); muxer.release();
            runOnUiThread(() -> {
                progressLabel.setText("\n Finished");
                progressText.setText("100%");
                circularProgressBar.setVisibility(INVISIBLE);
            });

            sharedVideoUri = null;


        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            Log.e("Squeezer", "Exception in renderAndMux", e);
        }
    }

}