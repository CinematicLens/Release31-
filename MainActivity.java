
package com.squeezer.app;

import static android.view.View.INVISIBLE;


import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.*;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.opengl.*;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.os.ParcelFileDescriptor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.util.Log;

import com.android.billingclient.api.ProductDetails;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREF_NAME = "BillingPrefs";
    private static final String PREF_KEY_TRIAL_START = "trial_start"; // ✅ NEW
    private float selectedFactor = -1f; // no default
    private boolean isResolutionSelected = false;

    private static final int REQUEST_CODE_PICK_FILE = 101;

    private Button startButton; // reference to enable/disable
    private static final int TRIAL_DAYS = 14;
    private final List<Button> allFactorButtons = new ArrayList<>();
    private final List<Button> allResolutionButtons = new ArrayList<>();
    private Uri sharedImageUri;
    private BillingManager billingManager;

    private int selectedVideoResId = 0; // 0=Original, 1=720p, 2=1080p, 3=4K
    private int selectedImageResId = 0; // 0=Original, 1=8MP, 2=12MP, 3=24MP

    private Uri sharedVideoUri = null;
    private static final int REQUEST_CODE_PERMISSIONS = 102;

    private boolean isDesqueezeEnabled = true;
    private boolean isSimulatorMode = false;
    private boolean isImageMode = false;
    private FrameLayout progressOverlay;
    private ProgressBar progressBar;
    private TextView progressText;
    private ProgressBar circularProgressBar;
    private static final String TAG = "Desqueeze";
    private int lastPercent = 0;
    TextView progressLabel = null;
    private TextView priceMessage;
    boolean isPurchased =  false;
    private Button pickFileButton;
    private String price;

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

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || intent.getAction() == null || intent.getType() == null) return;

        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            if (intent.getType().startsWith("video/")) {
                sharedVideoUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                isImageMode = false;
            } else if (intent.getType().startsWith("image/")) {
                sharedImageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                isImageMode = true;
            }
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String type = getContentResolver().getType(uri);
                if (type != null && type.startsWith("image/")) {
                    sharedImageUri = uri;
                    isImageMode = true;
                } else if (type != null && type.startsWith("video/")) {
                    sharedVideoUri = uri;
                    isImageMode = false;
                }

            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        if (hasPermissions()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                handleIncomingIntent(getIntent());  // ✅ Delay intent processing

                    renderSelectionUI();           // ✅ Safe UI render

            }, 300); // Delay 300ms to allow permission popup to fully close
        } else {
            Toast.makeText(this, "Permission required", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private String getVertexShaderCode(float factor, boolean isPortrait) {
        if (isPortrait) {
            // No stretch/squeeze in portrait — keep UVs as-is
            return
                    "uniform mat4 uMVPMatrix;\n" +
                            "attribute vec4 aPosition;\n" +
                            "attribute vec2 aTexCoord;\n" +
                            "varying vec2 vTexCoord;\n" +
                            "void main() {\n" +
                            "  gl_Position = uMVPMatrix * aPosition;\n" +
                            "  vTexCoord = aTexCoord;\n" +
                            "}";
        } else {
            // Apply horizontal desqueeze for landscape
            return
                    "uniform mat4 uMVPMatrix;\n" +
                            "attribute vec4 aPosition;\n" +
                            "attribute vec2 aTexCoord;\n" +
                            "varying vec2 vTexCoord;\n" +
                            "void main() {\n" +
                            "  gl_Position = uMVPMatrix * aPosition;\n" +
                            "  float x = aTexCoord.x * " + factor + ";\n" +
                            "  vTexCoord = vec2(min(x, 1.0), aTexCoord.y);\n" +
                            "}";
        }
    }




    private String getFragmentShaderCode() {
        return "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}";
    }
    private void checkPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses new media permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            // For Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        // Also request audio if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_CODE_PERMISSIONS);
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        checkPermissions();

        if (!hasPermissions()) {
            // Delay to allow system UI to fully appear
            new Handler().postDelayed(() -> requestPermissionsSafe(), 500);
            return;
        }

        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null) {
            if (intent.getType().startsWith("video/")) {
                sharedVideoUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                isImageMode = false;
            } else if (intent.getType().startsWith("image/")) {
                sharedImageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                isImageMode = true;
            }
        }

       billingManager = new BillingManager(this, new BillingManager.BillingCallback() {
            @Override
            public void onPurchaseComplete() {
                isPurchased = true;
                Toast.makeText(MainActivity.this, "Desqueeze unlocked!", Toast.LENGTH_SHORT).show();

            }

            @Override
            public void onBillingError(String error) {
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });

// ✅ Conditionally render UI based on trial or purchase status

            renderSelectionUI();


    }
    private String loadLocalizedPriceMessage(BillingManager billingManager) {
        billingManager.queryPrice(productDetails -> {
            ProductDetails.OneTimePurchaseOfferDetails offer = productDetails.getOneTimePurchaseOfferDetails();
            if (offer != null) {
                price = offer.getFormattedPrice(); // e.g., ₹499.00, $5.99
            } else {
                price = ""; // Fallback if pricing info not available
            }
        });
        return price;
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
    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        startActivityForResult(Intent.createChooser(intent, "Select Image or Video"), REQUEST_CODE_PICK_FILE);
    }
    private void renderSelectionUI() {

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 100, 40, 100);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.setVerticalScrollBarEnabled(true);
        layout.setBackgroundColor(Color.parseColor("#795548"));

        TextView guidance = new TextView(this);
        guidance.setText("\uD83D\uDCA1 Enjoy free access to all premium features for a limited time. \n\n" +
                "1. Load/Share a video or image from your gallery.\n" +
                "2. Choose a mode:\n" +
                "   • Simulator – Anamorphic squeezed \n" +
                "   • Desqueeze –Anamorohic desqueezed\n" +
                "   • Image – –Anamorohic desqueezed\n" +
                "   • LUT – cinematic color grading image and video\n" +
                "3. Outputs saved in DCIM/Desqueezed, DCIM/Squeezed, or DCIM/LUTProcessed.");

        guidance.setTextSize(18F);
        guidance.setPadding(0, 0, 0, 40);
        guidance.setTextColor(Color.WHITE);
        layout.addView(guidance);
        ScrollView scrollView = new ScrollView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            scrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int left = insets.getSystemWindowInsetLeft();
                    int top = insets.getSystemWindowInsetTop();
                    int right = insets.getSystemWindowInsetRight();
                    int bottom = insets.getSystemWindowInsetBottom();

                    v.setPadding(left, top, right, bottom);
                    return insets.consumeSystemWindowInsets();
                }
            });
        } else {
            int statusBarHeight = 0;

// Try to get actual status bar height from system resources
            int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                statusBarHeight = getResources().getDimensionPixelSize(resourceId);
            } else {
                // If not found, use a default of 24dp
                statusBarHeight = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
            }

// Apply only top padding, or add left/right/bottom if needed
            scrollView.setPadding(
                    scrollView.getPaddingLeft(),
                    statusBarHeight,
                    scrollView.getPaddingRight(),
                    scrollView.getPaddingBottom()
            );
        }

        
        addMenuButton(layout, "\uD83C\uDFA5 Anamorphic Simulator Squeeze", () -> {

                Intent simulatorIntent = new Intent(MainActivity.this, SimulatorActivity.class);
                simulatorIntent.setAction(Intent.ACTION_SEND);
                simulatorIntent.setType("video/*");
                simulatorIntent.putExtra(Intent.EXTRA_STREAM, sharedVideoUri);
                startActivity(simulatorIntent);

        });

        addMenuButton(layout, "\uD83C\uDFA6  Anamorphic Desqueeze Mode", () -> {
            isSimulatorMode = false;
            isImageMode = false;
            renderFactorSelectionUI();

        });
        addMenuButton(layout, "\uD83C\uDFAC LUT Video Color Grading", () -> {
            Intent lutIntent = new Intent(MainActivity.this, LUTPreviewActivity.class);
            lutIntent.setAction(Intent.ACTION_SEND);
            lutIntent.setType("video/*");
            lutIntent.putExtra(Intent.EXTRA_STREAM, sharedVideoUri);
            startActivity(lutIntent);
        });





        addMenuButton(layout, "\uD83D\uDDBC️ Image Desqueeze Mode", () -> {

            isImageMode = true;
            isSimulatorMode = false;
           // Toast.makeText(this, "Image Mode selected.", Toast.LENGTH_SHORT).show();
            renderFactorSelectionUI();
        });

        TextView note = new TextView(this);
        note.setText("\u26A0\uFE0F Note: Anamorphic Simulator does not generate true anamorphic footage. It is only intended for testing aspect ratio previews. For accurate results, use real anamorphic footage.");
        note.setTextSize(16F);
        note.setTextColor(Color.WHITE);
        note.setPadding(0, 50, 0, 0);
        layout.addView(note);
        scrollView.addView(layout);
        // ✅ wrap layout in scrollView
        setContentView(scrollView);


    }

    private void addMenuButton(ViewGroup layout, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18f);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 20, 0, 0);
        button.setLayoutParams(params);
        button.setOnClickListener(v -> action.run());
        layout.addView(button);
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

    private void renderFactorSelectionUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 300, 40, 80);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.setBackgroundColor(Color.parseColor("#795548"));

        TextView factorLabel = new TextView(this);
        factorLabel.setText(isImageMode ? " 🖼  Anamorphic DeSqueeze Image" : " 🎞  Anamorphic DeSqueeze Video");
        factorLabel.setTextSize(22F);
        factorLabel.setPadding(0, 0, 0, 30);
        factorLabel.setTextColor(Color.WHITE);
        layout.addView(factorLabel);

        // --- Factor Selection ---
        TextView ratioLabel = new TextView(this);
        ratioLabel.setText("Select Ratio (H-Expansion)");
        ratioLabel.setTextSize(15F);
        ratioLabel.setTextColor(Color.WHITE);
        layout.addView(ratioLabel);

        LinearLayout factorTabLayout = new LinearLayout(this);
        factorTabLayout.setOrientation(LinearLayout.HORIZONTAL);
        factorTabLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        factorTabLayout.setPadding(0, 10, 0, 30);
        layout.addView(factorTabLayout);


        // --- Custom button + inline input row ---
        Button customBtn = new Button(this);
        customBtn.setText("Custom");
        styleTabButton(customBtn);
        factorTabLayout.addView(customBtn);
        allFactorButtons.add(customBtn);

// 🔒 lock look if not purchased (same pattern as 1.33x/1.55x)
        boolean customRequiresPurchase = true;
        boolean customHasAccess = !customRequiresPurchase || isPurchased;
        customBtn.setAlpha(customHasAccess ? 1.0f : 0.5f);

// Inline row (hidden until user taps Custom)
        final LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_HORIZONTAL);
        customRow.setPadding(0, dp(8), 0, dp(28));
        customRow.setVisibility(View.GONE);

// Input background styles (normal + focused)
        final GradientDrawable normalBg = new GradientDrawable();
        normalBg.setCornerRadius(dp(10));
        normalBg.setColor(Color.parseColor("#263238"));

        final GradientDrawable focusBg = new GradientDrawable();
        focusBg.setCornerRadius(dp(10));
        focusBg.setColor(Color.parseColor("#2B3A42"));
        focusBg.setStroke(dp(2), Color.parseColor("#90CAF9")); // highlight

// EditText (weight=1 so it never collapses)
        final EditText customFactorInput = new EditText(this);
        customFactorInput.setHint("Enter ratio e.g. 1.78");
        customFactorInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        customFactorInput.setSingleLine(true);
        customFactorInput.setTextColor(Color.WHITE);
        customFactorInput.setBackground(normalBg);
        customFactorInput.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        customRow.addView(customFactorInput, ip);

// Set button (force WRAP_CONTENT so it won't occupy the whole row)
        Button setCustomBtn = new Button(this);
        setCustomBtn.setText("Set");
        styleSoftButton(setCustomBtn, "#607D8B");
// override any MATCH_PARENT from styleSoftButton
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        setLp.leftMargin = dp(12);
        setCustomBtn.setLayoutParams(setLp);
        setCustomBtn.setMinWidth(dp(72));
        customRow.addView(setCustomBtn);

// Toggle row + highlight on Custom click
        customBtn.setOnClickListener(v -> {
            // ✅ SAME BEHAVIOR AS 1.33x/1.55x: paywall if not purchased
            if (customRequiresPurchase && !isPurchased) {
                Toast.makeText(this, "This option (Custom) requires a premium upgrade.", Toast.LENGTH_SHORT).show();
                renderUnlockUI();
                return;
            }

            if (customRow.getVisibility() != View.VISIBLE) customRow.setVisibility(View.VISIBLE);

            // prefill last value if available
            if (selectedFactor >= 1.0f && selectedFactor <= 3.0f) {
                customFactorInput.setText(String.format(Locale.US, "%.2f", selectedFactor));
                customFactorInput.setSelection(customFactorInput.getText().length());
            } else {
                customFactorInput.setText("");
            }

            // focus + highlight + keyboard (unchanged)
            customFactorInput.requestFocus();
            customFactorInput.setBackground(focusBg);
            customFactorInput.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(customFactorInput, InputMethodManager.SHOW_IMPLICIT);
            });

            // visually select Custom (unchanged)
            for (Button b : allFactorButtons) {
                GradientDrawable un = new GradientDrawable();
                un.setCornerRadius(dp(15));
                un.setColor(Color.parseColor("#263238"));
                b.setBackground(un);
                b.setTextColor(Color.parseColor("#B0BEC5"));
            }
            GradientDrawable sel = new GradientDrawable();
            sel.setCornerRadius(dp(15));
            sel.setColor(Color.parseColor("#607D8B"));
            customBtn.setBackground(sel);
            customBtn.setTextColor(Color.WHITE);
        });

// focus border toggle
        customFactorInput.setOnFocusChangeListener((v, hasFocus) ->
                customFactorInput.setBackground(hasFocus ? focusBg : normalBg));

// Apply via Set
        setCustomBtn.setOnClickListener(v -> {
            if (applyCustomFactor(customBtn, customFactorInput)) {
                // optional: collapse; comment this out if you want to keep it open
                customRow.setVisibility(View.GONE);
            }
        });

// Apply via keyboard Done/Enter
        customFactorInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        customFactorInput.setOnEditorActionListener((tv, actionId, event) -> {
            boolean enter = event != null &&
                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                return applyCustomFactor(customBtn, customFactorInput);
            }
            return false;
        });

// add the row beneath the factor buttons
        layout.addView(customRow);
        addFactorButton(factorTabLayout, "1.33x", 1.33f);
        addFactorButton(factorTabLayout, "1.55x", 1.55f);
        addFactorButton(factorTabLayout, "2.0x", 2.0f);

        // --- Resolution Selection ---
        TextView resLabel = new TextView(this);
        resLabel.setText(isImageMode ? "Select Output Image Size" : "Select Output Video Resolution");
        resLabel.setTextSize(15F);
        resLabel.setTextColor(Color.WHITE);
        layout.addView(resLabel);

        LinearLayout resTabLayout = new LinearLayout(this);
        resTabLayout.setOrientation(LinearLayout.HORIZONTAL);
        resTabLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        resTabLayout.setPadding(0, 10, 0, 30);
        layout.addView(resTabLayout);

        if (isImageMode) {
            addResolutionButton(resTabLayout, "Original", 0, false, true);
            addResolutionButton(resTabLayout, "8 MP", 1, false, true);
            addResolutionButton(resTabLayout, "12 MP", 2, false, true);
            addResolutionButton(resTabLayout, "24 MP", 3, false, true);
        } else {


            addResolutionButton(resTabLayout, "Original", 0, true, true);
            addResolutionButton(resTabLayout, "720p", 1, true, true);
            addResolutionButton(resTabLayout, "1080p", 2, true, true);
            addResolutionButton(resTabLayout, "4K", 3, true, true);
        }
         pickFileButton = new Button(this);
        layout.addView(pickFileButton);
        if (isImageMode) {
        pickFileButton.setText("Load Image from Gallery");
        } else  {
            pickFileButton.setText("Load Video from Gallery");
        }
        pickFileButton.setTextSize(20f);
        pickFileButton.setPadding(0, 0, 0, 40);
        styleSoftButton(pickFileButton, "#BDBDBD");
        pickFileButton.setTextColor(Color.BLACK);

        pickFileButton.setOnClickListener(v -> pickFile());

        // --- Progress Label ---
        progressLabel = new TextView(this);
        progressLabel.setText("\nProgress...");
        progressLabel.setTextSize(18F);
        progressLabel.setTextColor(Color.WHITE);
        layout.addView(progressLabel);
        progressLabel.setVisibility(INVISIBLE);
        // --- Optional Progress Bar (Video only) ---
        if (!isImageMode) {
            circularProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
            circularProgressBar.setIndeterminate(false);
            circularProgressBar.setMax(100);
            LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(200, 200);
            circleParams.gravity = Gravity.CENTER;
            circularProgressBar.setLayoutParams(circleParams);

            progressText = new TextView(this);
            progressText.setTextSize(18f);
            progressText.setGravity(Gravity.CENTER);

            layout.addView(circularProgressBar);
            layout.addView(progressText);
            circularProgressBar.setVisibility(View.GONE);
            progressText.setVisibility(View.GONE);
        }


        // --- Desqueeze Button ---
        Button desqueezeButton = new Button(this);
        desqueezeButton.setText("Desqueeze!");
        styleSoftButton(desqueezeButton, "#607D8B"); // Keep it visually distinct
        desqueezeButton.setOnClickListener(v -> {
            StringBuilder errorMsg = new StringBuilder();

            if (selectedFactor < 1.0f) { // allow 1.00x
                Toast.makeText(this, "Please select a valid squeeze ratio.", Toast.LENGTH_LONG).show();
                return;
            }

            if (!isResolutionSelected) {
                Toast.makeText(this, "Please select an output resolution.", Toast.LENGTH_LONG).show();
                return;
            }

            if ((isImageMode && sharedImageUri == null) || (!isImageMode && sharedVideoUri == null)) {
                String type = isImageMode ? "image" : "video";
                Toast.makeText(this, "Please share a " + type + " file first.", Toast.LENGTH_LONG).show();
                return;
            }

            if (errorMsg.length() > 0) {
                Toast.makeText(this, "Missing Please :\n" + errorMsg.toString().trim(), Toast.LENGTH_LONG).show();
                return;
            }

            if (isImageMode) {
                new Thread(() -> desqueezeImage(sharedImageUri, selectedFactor)).start();
            } else {
                new Thread(() -> desqueezeVideo(sharedVideoUri, selectedFactor)).start();
            }
        });
        layout.addView(desqueezeButton);

        Button backButton = new Button(this);
        backButton.setText("Back to Mode Selection");

// Style: gray background, black text, rounded corners
        GradientDrawable backDrawable = new GradientDrawable();
        backDrawable.setCornerRadius(30f);
        backDrawable.setColor(Color.parseColor("#BDBDBD")); // light gray
        backButton.setBackground(backDrawable);
        backButton.setTextColor(Color.BLACK);

// Consistent padding
        backButton.setPadding(40, 30, 40, 30);

// Consistent margins and full width
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        backParams.setMargins(40, 20, 40, 20);
        backButton.setLayoutParams(backParams);

// ✅ Add the missing click listener
        backButton.setOnClickListener(v -> renderSelectionUI());

// Add to layout
        layout.addView(backButton);

// --- ScrollView wrapping ---
        ScrollView scrollView = new ScrollView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(left, top, right, bottom);
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
    private boolean applyCustomFactor(Button customBtn, EditText input) {



        String txt = input.getText().toString().trim();
        if (txt.isEmpty()) { Toast.makeText(this, "Enter a number like 1.78", Toast.LENGTH_SHORT).show(); return false; }
        try {
            float f = Float.parseFloat(txt);
            if (!Float.isFinite(f)) throw new NumberFormatException();
            if (f < 1.00f || f > 3.00f) { Toast.makeText(this, "Enter 1.00×–3.00×", Toast.LENGTH_SHORT).show(); return false; }

            selectedFactor = f;
            customBtn.setText(String.format(Locale.US, "Custom (%.2fx)", f));

            for (Button b : allFactorButtons) {
                GradientDrawable un = new GradientDrawable();
                un.setCornerRadius(dp(15));
                un.setColor(Color.parseColor("#263238"));
                b.setBackground(un);
                b.setTextColor(Color.parseColor("#B0BEC5"));
            }
            GradientDrawable sel = new GradientDrawable();
            sel.setCornerRadius(dp(15));
            sel.setColor(Color.parseColor("#607D8B"));
            customBtn.setBackground(sel);
            customBtn.setTextColor(Color.WHITE);

            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            Toast.makeText(this, String.format(Locale.US, "Custom %.2fx selected", f), Toast.LENGTH_SHORT).show();
            if (!isPurchased) {
                Toast.makeText(this, "Custom ratio requires a premium upgrade.", Toast.LENGTH_SHORT).show();
                renderUnlockUI();
                return false;
            }

            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show();
            return false;
        }
    }
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
    private void styleTabButton(Button button) {
        button.setAllCaps(false);
        button.setTextColor(Color.BLACK);
        button.setTextSize(14f);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(30f); // Rounded tab look
        drawable.setColor(Color.LTGRAY); // Default color
        button.setBackground(drawable);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f  // Equal width tabs
        );
        params.setMargins(10, 0, 10, 0);  // spacing between tabs
        button.setLayoutParams(params);
    }
    private void addFactorButton(ViewGroup layout, String label, float factor) {
        Button button = new Button(this);
        button.setText(label);
        styleTabButton(button);

        boolean requiresPurchase = "1.33x".equals(label) || "1.55x".equals(label);
        boolean hasAccess = !requiresPurchase || isPurchased;

        button.setAlpha(hasAccess ? 1.0f : 0.5f);
        button.setEnabled(true); // Always clickable

        button.setOnClickListener(v -> {
            if (requiresPurchase && !isPurchased) {
                Toast.makeText(this, "This option (" + label + ") requires a premium upgrade.", Toast.LENGTH_SHORT).show();
                renderUnlockUI();
                return;
            } else {
                Toast.makeText(this, label + " selected", Toast.LENGTH_SHORT).show();
            }

            selectedFactor = factor;

            for (Button b : allFactorButtons) {
                GradientDrawable unselected = new GradientDrawable();
                unselected.setCornerRadius(30f);
                unselected.setColor(Color.LTGRAY);
                b.setBackground(unselected);
                b.setTextColor(Color.BLACK);
            }

            GradientDrawable selectedDrawable = new GradientDrawable();
            selectedDrawable.setCornerRadius(30f);
            selectedDrawable.setColor(Color.parseColor("#4CAF50"));
            button.setBackground(selectedDrawable);
            button.setTextColor(Color.WHITE);
        });

        layout.addView(button);
        allFactorButtons.add(button);
    }


    private void addResolutionButton(ViewGroup layout, String label, int resId, boolean isVideo, boolean isEnabled) {
        Button button = new Button(this);
        button.setText(label);
        styleTabButton(button);

        button.setEnabled(isEnabled); // ✅ disable if false
        button.setAlpha(isEnabled ? 1.0f : 0.5f); // ✅ make it visually faded if disabled

        button.setOnClickListener(v -> {


            if (isVideo) {
                selectedVideoResId = resId;
            } else {
                selectedImageResId = resId;
            }

            isResolutionSelected = true;

            for (Button b : allResolutionButtons) {
                GradientDrawable unselected = new GradientDrawable();
                unselected.setCornerRadius(30f);
                unselected.setColor(Color.LTGRAY);
                b.setBackground(unselected);
                b.setTextColor(Color.BLACK);
            }

            GradientDrawable selectedDrawable = new GradientDrawable();
            selectedDrawable.setCornerRadius(30f);
            selectedDrawable.setColor(Color.parseColor("#4CAF50"));
            button.setBackground(selectedDrawable);
            button.setTextColor(Color.WHITE);
        });

        layout.addView(button);
        allResolutionButtons.add(button);
    }

    private void renderUnlockUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 140, 40, 140);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.setBackgroundColor(Color.parseColor("#000000")); // Changed to black

        loadLocalizedPriceMessage(billingManager);

        TextView title = new TextView(this);
        title.setText("🔓 Premium Feature Required");
        title.setTextSize(20F);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 40, 0, 30);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("To use 1.33x, 1.55x desqueeze ratios , please unlock premium access.");
        info.setTextSize(16F);
        info.setTextColor(Color.WHITE);
        info.setPadding(0, 0, 0, 30);
        layout.addView(info);

        Button unlockBtn = new Button(this);
        unlockBtn.setText(price != null ? "Unlock Now (" + price + ")" : "Unlock Now");
        unlockBtn.setTextColor(Color.WHITE);
        unlockBtn.setBackgroundColor(Color.parseColor("#212121")); // Dark gray button for black theme
        unlockBtn.setOnClickListener(v -> billingManager.launchPurchaseFlow(this));
        layout.addView(unlockBtn);

        Button backBtn = new Button(this);
        backBtn.setText("Go Back");
        backBtn.setOnClickListener(v -> renderSelectionUI());
        layout.addView(backBtn);

        ScrollView scrollView = new ScrollView(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom()
                );
                return insets.consumeSystemWindowInsets();
            });
        } else {
            int statusBarHeight = 0;
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                statusBarHeight = getResources().getDimensionPixelSize(resId);
            }
            scrollView.setPadding(0, statusBarHeight, 0, 0);
        }

        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(layout);
        setContentView(scrollView);
    }
    private int[] calculateTargetResolution(MediaFormat inputFormat, float factor, int resId) {
        int inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        float desqueezedWidth = inputWidth * factor;
        float desqueezedAspect = desqueezedWidth / inputHeight;

        int baseWidth;
        switch (resId) {
            case 3: baseWidth = 3840; break;
            case 2: baseWidth = 1920; break;
            case 1: baseWidth = 1280; break;
            default: baseWidth = (int) desqueezedWidth; break;
        }

        int baseHeight = Math.round(baseWidth / desqueezedAspect);
        return new int[]{baseWidth, baseHeight};
    }
    private boolean isValidVideoUri(Context context, Uri uri) {
        try {
            String type = context.getContentResolver().getType(uri);
            return type != null && type.startsWith("video/");
        } catch (Exception e) {
            return false;
        }
    }
    // Add this helper once in your class (top-level):
    @androidx.annotation.Nullable
    private static android.net.Uri resolveToMediaUri(android.content.Context ctx, android.net.Uri uri) {
        if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
            try {
                String docId = android.provider.DocumentsContract.getDocumentId(uri); // e.g. "video:1000000033"
                String[] parts = docId.split(":");
                if (parts.length == 2) {
                    long id = Long.parseLong(parts[1]);
                    return android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                }
            } catch (Exception ignore) {}
        }
        return null;
    }

    private void desqueezeVideo(android.net.Uri videoUri, float factor) {
        try {
            // ---------- OPEN EXTRACTOR (robust + logs) ----------
            MediaExtractor extractor = new MediaExtractor();

            android.net.Uri openUri = videoUri;
            android.net.Uri media = resolveToMediaUri(this, videoUri);
            if (media != null) openUri = media;

            boolean opened = false;
            try {
                extractor.setDataSource(this, openUri, null);   // preferred for content://
                opened = true;
            } catch (Exception e1) {
                Log.e(TAG, "Extractor setDataSource(Context,Uri) failed. "
                        + "uri=" + videoUri + " resolved=" + openUri
                        + " scheme=" + videoUri.getScheme()
                        + " authority=" + videoUri.getAuthority(), e1);

                // Fallback: stream to a temp file (handles UNKNOWN_LENGTH / non-seekable)
                try (InputStream in = getContentResolver().openInputStream(videoUri)) {
                    if (in == null) throw new java.io.FileNotFoundException("openInputStream returned null");
                    java.io.File tmp = java.io.File.createTempFile("in_", ".bin", getCacheDir());
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[1 << 20]; int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    extractor.setDataSource(tmp.getAbsolutePath());
                    tmp.deleteOnExit();
                    opened = true;
                } catch (Exception e2) {
                    Log.e(TAG, "Extractor temp-file fallback failed for uri=" + videoUri, e2);
                }
            }

            if (!opened) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Unable to open video (see logcat)", Toast.LENGTH_LONG).show());
                try { extractor.release(); } catch (Exception ignore) {}
                return;
            }

            // ---------- FIND VIDEO TRACK ----------
            int videoTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    inputFormat = f;
                    break;
                }
            }
            if (videoTrack == -1 || inputFormat == null) {
                try { extractor.release(); } catch (Exception ignore) {}
                runOnUiThread(() -> Toast.makeText(this, "No video track found.", Toast.LENGTH_LONG).show());
                return;
            }

            // ---------- ROTATION/SHADERS ----------
            int rotationDegrees = getRotationDegrees(this, videoUri);
            boolean rotate90 = (rotationDegrees == 90 || rotationDegrees == 270);
            String vertexShader = getVertexShaderCode(factor, rotate90);
            String fragmentShader = getFragmentShaderCode();

            // Hand off to the rest of your pipeline
            renderAndMux(extractor, inputFormat, videoTrack, factor, vertexShader, fragmentShader, videoUri);

        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }


    private void renderAndMux(MediaExtractor extractor, MediaFormat inputFormat, int  videoTrack , float factor, String vertexShaderCode, String fragmentShaderCode, Uri videoUri) {
        // runOnUiThread(() -> Toast.makeText(this, "🔧 Starting EGL + MediaCodec setup", Toast.LENGTH_SHORT).show());
        runOnUiThread(() -> {
            circularProgressBar.setVisibility(View.VISIBLE);
            progressText.setVisibility(View.VISIBLE);
            circularProgressBar.setProgress(0);
            progressText.setText("0%");
        });

        try {
            int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

// Get rotation from MediaMetadataRetriever
            int rotationDegrees = getRotationDegrees(this, videoUri);
            boolean rotate90 = (rotationDegrees == 90 || rotationDegrees == 270);

// Step 1: Swap width/height if rotated
            int correctedWidth = rotate90 ? height : width;
            int correctedHeight = rotate90 ? width : height;

// Step 2: Apply horizontal desqueeze only if landscape
            int outputWidth = rotate90 ? correctedWidth : (int)(correctedWidth * factor);
            int outputHeight = rotate90 ? correctedHeight : correctedHeight; // no factor in portrait

// ✅ Log it for debug
            Log.d("Rotation", "Rotation: " + rotationDegrees + "°. Output resolution = " + outputWidth + " x " + outputHeight);

// Step 2: Apply horizontal desqueeze to width only
            long totalDurationUs = 0;
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                totalDurationUs = inputFormat.getLong(MediaFormat.KEY_DURATION);
            }

            String folderName = isSimulatorMode ? "Anamorphic Simulator" : "Desqueezed";
            String filePrefix = isSimulatorMode ? "simulated_video_" : "desqueezed_video_";
            File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), folderName);
            outputDir.mkdirs();

            String outputPath = new File(outputDir, filePrefix + System.currentTimeMillis() + ".mp4").getAbsolutePath();
            MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int audioTrackIndex = -1;
            int muxAudioTrackIndex = -1;
            MediaCodec decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));

// Step 1: Read input size
            int inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
            int inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

// ✅ Adjusted: For landscape apply factor, portrait = no factor
            float stretchedWidth;
            float stretchedHeight;

            if (rotate90) {
                // Portrait — do not apply desqueeze factor
                stretchedWidth = inputHeight * factor;
                stretchedHeight = inputWidth;
            } else {
                // Landscape — apply horizontal desqueeze
                stretchedWidth = inputWidth * factor;
                stretchedHeight = inputHeight;
            }
            float desqueezedAspect = stretchedWidth / stretchedHeight;

// Step 3: Choose base width based on user selection
            int baseWidth;
            switch (selectedVideoResId) {
                case 3:  // 4K
                    baseWidth = 3840;
                    break;
                case 2:  // 1080p
                    baseWidth = 1920;
                    break;
                case 1:  // 720p
                    baseWidth = 1280;
                    break;
                case 0:
                default: // Original resolution (after desqueeze/stretch applied)
                    baseWidth = (int) stretchedWidth;
                    break;
            }

// Step 4: Scale height to maintain desqueezed aspect ratio
            int baseHeight = Math.round(baseWidth / desqueezedAspect);

// Final output canvas resolution
            int targetWidth = baseWidth;
            int targetHeight = baseHeight;

// Final output canvas resolution (no padding needed)
            int finalWidth = targetWidth;
            int finalHeight = targetHeight;

            MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
            MediaFormat outputFormat = MediaFormat.createVideoFormat("video/avc", finalWidth, finalHeight);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, finalWidth * finalHeight * 6); // Dynamic bitrate
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            try {
                encoder = MediaCodec.createEncoderByType("video/avc");

                outputFormat = MediaFormat.createVideoFormat("video/avc", finalWidth, finalHeight);
                outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, finalWidth * finalHeight * 6);
                outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception e) {
                try {
                    if (selectedVideoResId == 3)  {
                    encoder = MediaCodec.createEncoderByType("video/avc");
                    outputFormat = MediaFormat.createVideoFormat("video/avc", finalWidth, finalHeight);
                    outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, finalWidth * finalHeight * 6);
                    outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                    outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                    encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    }    } catch (Exception ex) {
                    encoder.stop();
                    runOnUiThread(() ->
                            Toast.makeText(this, "Device Doesn't Support 4k " + ex.getMessage(), Toast.LENGTH_LONG).show()
                    );

                    return;
                }
            }

          //  MediaCodec encoder;
            try {
                encoder = MediaCodec.createEncoderByType("video/avc");
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                //  runOnUiThread(() -> Toast.makeText(this, "✅ Hardware encoder configured", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                // runOnUiThread(() -> Toast.makeText(this, "⚠️ Hardware encoder failed, trying software fallback", Toast.LENGTH_LONG).show());
                Log.w(TAG, "⚠️ Hardware encoder error", e);

                encoder = MediaCodec.createByCodecName("OMX.google.h264.encoder");

                outputFormat.setInteger(MediaFormat.KEY_WIDTH, 1280);
                outputFormat.setInteger(MediaFormat.KEY_HEIGHT, 720);
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);  // 8 Mbps
                outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                // runOnUiThread(() -> Toast.makeText(this, "✅ Software encoder (OMX.google) configured", Toast.LENGTH_SHORT).show());
            }

            Surface inputSurface = encoder.createInputSurface();
            encoder.start();
            // runOnUiThread(() -> Toast.makeText(this, "🚀 Encoder started", Toast.LENGTH_SHORT).show());

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


            if (totalDurationUs <= 0 && inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                totalDurationUs = inputFormat.getLong(MediaFormat.KEY_DURATION);
            }
            if (totalDurationUs <= 0) {
                totalDurationUs = 1_000_000;
            }



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
            extractor.selectTrack(videoTrack);

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            boolean muxerStarted = false;
            int trackIndex = -1;



            while (!outputDone) {
                // 🟢 Queue input to decoder
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;

                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                            // runOnUiThread(() -> Toast.makeText(this, "📥 Input queued: " + sampleSize + " bytes", Toast.LENGTH_SHORT).show());


                        }
                    }
                }

                // 🟡 Get output from decoder
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    surfaceTexture.updateTexImage();
                    finalWidth = Math.min(finalWidth, targetWidth);
                    finalHeight = Math.min(finalHeight, targetHeight);

// Compute centered viewport within encoder canvas (which is targetWidth × targetHeight)
                    int xOffset = Math.max(0, (targetWidth - finalWidth) / 2);
                    int yOffset = Math.max(0, (targetHeight - finalHeight) / 2);

// Set centered OpenGL viewport
                    GLES20.glViewport(xOffset, yOffset, finalWidth, finalHeight);
                   // GLES20.glViewport(0, 0, outputWidth, outputHeight);
                    GLES20.glClearColor(0f, 0f, 0f, 1f); // Optional: solid black
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
                    GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
                    GLES20.glEnableVertexAttribArray(aPosition);
                    GLES20.glEnableVertexAttribArray(aTexCoord);
                    GLES20.glUniform1i(uTexture, 0);

// Log attribute locations
                    Log.d("glUniform1i", "✔ aPosition = " + aPosition + ", aTexCoord = " + aTexCoord + ", uTexture = " + uTexture);

// MVP matrix setup
                    float[] mvpMatrix = new float[16];
                    android.opengl.Matrix.setIdentityM(mvpMatrix, 0);
                    Log.d("glUniform1i", "📐 Set identity matrix");

                    if (rotationDegrees == 90) {
                        android.opengl.Matrix.rotateM(mvpMatrix, 0, 90, 0f, 0f, 1f);
                        android.opengl.Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f); // Flip horizontally to fix portrait
                        Log.d("glUniform1i", "↪️ Applied 90° rotation and horizontal flip for portrait");
                    } else if (rotationDegrees == 270) {
                        android.opengl.Matrix.rotateM(mvpMatrix, 0, 270, 0f, 0f, 1f);
                        Log.d("glUniform1i", "↪️ Applied 270° rotation");
                    } else {
                        Log.d("glUniform1i", "➡️ No rotation applied");
                    }

                    int mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix");
                    if (mvpMatrixHandle == -1) {
                        Log.e("glUniform1i", "❌ Could not find uMVPMatrix in shader!");
                    } else {
                        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
                        Log.d("glUniform1i", "✅ Set uMVPMatrix uniform");
                    }

// Draw
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    Log.d("glUniform1i", "🎬 Issued glDrawArrays");





                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, info.presentationTimeUs * 1000);
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                    decoder.releaseOutputBuffer(outIndex, true);

                    long progressUs = info.presentationTimeUs;
                    int currentPercent = (int) ((progressUs * 100) / totalDurationUs);
                    currentPercent = Math.min(100, Math.max(0, currentPercent));



                    //runOnUiThread(() -> Toast.makeText(this, "🎨 Frame rendered", Toast.LENGTH_SHORT).show());

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream();
                        //runOnUiThread(() -> Toast.makeText(this, "🚫 Decoder EOS reached", Toast.LENGTH_SHORT).show());
                    }
                }

                // 🔴 Drain encoder
                int encOut = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new RuntimeException("Format changed twice");
                    MediaFormat newFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(newFormat);
                    // Handle audio
                    int audioSourceIndex = -1;
                    MediaFormat audioFormat = null;
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat f = extractor.getTrackFormat(i);
                        if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                            audioSourceIndex = i;
                            audioFormat = f;
                            break;
                        }
                    }

                    if (audioFormat != null) {
                        audioTrackIndex = muxer.addTrack(audioFormat);
                    }
                    muxer.start();
                    muxerStarted = true;

                    if (audioTrackIndex >= 0) {
                        MediaExtractor audioExtractor = new MediaExtractor();

                        android.net.Uri aOpen = sharedVideoUri;
                        android.net.Uri aMedia = resolveToMediaUri(this, sharedVideoUri);
                        if (aMedia != null) aOpen = aMedia;

                        boolean audioOpened = false;
                        try {
                            audioExtractor.setDataSource(this, aOpen, null);   // preferred for content://
                            audioOpened = true;
                        } catch (Exception e1) {
                            Log.e(TAG, "Audio setDataSource(Context,Uri) failed. uri=" + sharedVideoUri, e1);
                            try (InputStream in = getContentResolver().openInputStream(sharedVideoUri)) {
                                if (in != null) {
                                    java.io.File tmpA = java.io.File.createTempFile("in_a_", ".bin", getCacheDir());
                                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmpA)) {
                                        byte[] buf = new byte[1 << 20]; int n;
                                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                                    }
                                    audioExtractor.setDataSource(tmpA.getAbsolutePath());  // fallback for UNKNOWN_LENGTH
                                    tmpA.deleteOnExit();
                                    audioOpened = true;
                                }
                            } catch (Exception e2) {
                                Log.e(TAG, "Audio temp-file fallback failed. uri=" + sharedVideoUri, e2);
                            }
                        }
                        audioExtractor.selectTrack(audioSourceIndex);
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
                            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                                bufferFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                            bufferInfo.flags = bufferFlags;
                            muxer.writeSampleData(audioTrackIndex, buffer, audioInfo);
                            audioExtractor.advance();
                        }

                        audioExtractor.release();
                    }

                    //runOnUiThread(() -> Toast.makeText(this, "🎬 Muxer started", Toast.LENGTH_SHORT).show());
                } else if (encOut >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encOut);
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                        // runOnUiThread(() -> Toast.makeText(this, "📼 Encoded frame written", Toast.LENGTH_SHORT).show());
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
            sharedImageUri = null;
            sharedVideoUri = null;
        } catch (Exception e) {
         //   runOnUiThread(() -> Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            Log.e(TAG, "Error during renderAndMux", e);
        }
    }
    private void desqueezeImage(Uri imageUri, float factor) {
        try {
            InputStream input = getContentResolver().openInputStream(imageUri);
            Bitmap original = BitmapFactory.decodeStream(input);
            input.close();

            // Get EXIF rotation
            input = getContentResolver().openInputStream(imageUri);
            ExifInterface exif = new ExifInterface(input);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            input.close();

            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  matrix.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            }

            // Apply desqueeze or squeeze factor
            float scaleX = isSimulatorMode ? (1f / factor) : factor;
            matrix.postScale(scaleX, 1.0f);

            Bitmap transformed = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);

            // Megapixel resizing setup
            int targetWidth = transformed.getWidth();
            int targetHeight = transformed.getHeight();

            switch (selectedImageResId) {
                case 1: targetWidth = 3264; targetHeight = 2448; break; // 8 MP
                case 2: targetWidth = 4032; targetHeight = 3024; break; // 12 MP
                case 3: targetWidth = 6000; targetHeight = 4000; break; // 24 MP
                // 0 = original
            }

            // Preserve aspect ratio
            float inputAspect = (float) transformed.getWidth() / transformed.getHeight();
            float targetAspect = (float) targetWidth / targetHeight;

            int finalWidth, finalHeight;
            if (inputAspect > targetAspect) {
                finalWidth = targetWidth;
                finalHeight = (int)(targetWidth / inputAspect);
            } else {
                finalHeight = targetHeight;
                finalWidth = (int)(targetHeight * inputAspect);
            }

            // Scale proportionally
            Bitmap scaled = Bitmap.createScaledBitmap(transformed, finalWidth, finalHeight, true);

            // Pad to exact target canvas (optional)
            Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.BLACK); // You can change to WHITE if preferred
            canvas.drawBitmap(scaled, (targetWidth - finalWidth) / 2f, (targetHeight - finalHeight) / 2f, null);

            // Save to DCIM/Desqueezed
            File picturesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Desqueezed");
            picturesDir.mkdirs();

            String filename = "desqueezed_" + factor + "x_" + System.currentTimeMillis() + ".jpg";
            File file = new File(picturesDir, filename);

            FileOutputStream out = new FileOutputStream(file);
            output.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.close();

            MediaScannerConnection.scanFile(
                    getApplicationContext(),
                    new String[]{file.getAbsolutePath()},
                    new String[]{"image/jpeg"}, null
            );

            runOnUiThread(() ->
                    Toast.makeText(this, "🎉 Finished"  , Toast.LENGTH_LONG).show());
            sharedImageUri = null;
            sharedVideoUri = null;
        } catch (Exception e) {
          //  runOnUiThread(() ->
                   // Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

}
