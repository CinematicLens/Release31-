// File: LUTPreviewActivity.java
package com.squeezer.app;

import static android.view.View.INVISIBLE;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;

public class LUTPreviewActivity extends AppCompatActivity {

    public static final int REQUEST_CODE_PICK_FILE = 101;      // media (image/video)
    public static final int REQUEST_CODE_IMPORT_LUT = 201;     // (kept for legacy flows)
    private static final int REQUEST_CODE_PERMISSIONS = 102;


    @Nullable private Uri lastImageUri = null;
    private boolean didAutoNeutralizeForImage = false;
    // --- Video/preview session
    private LUTPreviewHelper.Session videoSession;
    private android.os.Handler previewHandler = new android.os.Handler();
    private Runnable previewRunnable;
    private Uri selectedUri;

    /** lutId: "asset:Neutral_Look.cube" or "file:/abs/path.cube" */
    private String selectedLutId = LutManager.toAssetId("Neutral_Look.cube");

    private FrameLayout previewContainer;
    private ImageView previewImage;

    // --- Grading params
    private float tint = 0.0f;
    private float contrast = 1.0f;
    private float saturation = 1.0f;

    // Advanced
    private float exposure = 0.0f;
    private float vibrance = 0.0f;
    private float temp = 0.0f;
    private float greenMagenta = 0.0f;
    private float highlightRoll = 0.0f;
    private float vignetteStrength = 0.0f;
    private float vignetteSoftness = 0.0f;

    private ProgressBar circularProgressBar;
    private TextView progressText;
    private TextView progressLabel;
    private Button processButton;

    private boolean isImage = false;
    private boolean isExporting = false;

    // Billing
    private BillingManager billingManager;
    private boolean isPurchased = false;

    // ---- Effects selector ----
    private enum Effect { CONTRAST("Contrast"), SATURATION("Saturation"), TINT("Tint"),
        EXPOSURE("Exposure"), VIBRANCE("Vibrance"), TEMP_TINT("Temp/Tint"),
        HIGHLIGHT_ROLL("Highlight Roll"), VIGNETTE("Vignette");
        final String label; Effect(String label) { this.label = label; } }
    private LinearLayout effectsRow;
    private LinearLayout controlsArea;
    private Effect selectedEffect = Effect.CONTRAST;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
    }

    private void handleSharedIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            String type = intent.getType();
            if (type != null && type.startsWith("image/") && sharedUri != null) {
                isImage = true;
                selectedUri = sharedUri;
                updatePreview();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions();

        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null) {
            if (intent.getType().startsWith("video/")) {
                selectedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                isImage = false;
            } else if (intent.getType().startsWith("image/")) {
                selectedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                isImage = true;
            }
        }

        billingManager = new BillingManager(this, new BillingManager.BillingCallback() {
            @Override public void onPurchaseComplete() {
                isPurchased = true;
                Toast.makeText(LUTPreviewActivity.this, "Pro unlocked!", Toast.LENGTH_SHORT).show();
            }
            @Override public void onBillingError(String error) {
                Toast.makeText(LUTPreviewActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });

        // Root vertical layout
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 120, 40, 80);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.setBackgroundColor(Color.parseColor("#795548"));

        TextView lutLabel = new TextView(this);
        lutLabel.setText("Cinematic Look");
        lutLabel.setTextSize(22f);
        lutLabel.setTypeface(Typeface.DEFAULT_BOLD);
        lutLabel.setTextColor(Color.parseColor("#E9D8A6"));
        lutLabel.setGravity(Gravity.CENTER);
        lutLabel.setPadding(0, 32, 0, 16);
        layout.addView(lutLabel);

        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImage.setImageResource(R.drawable.preview_placeholder);

        previewContainer = new FrameLayout(this);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int previewHeight = (int) (screenWidth * 9f / 16f);
        previewContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, previewHeight));
        previewContainer.addView(previewImage);
        layout.addView(previewContainer);

        TextView note = new TextView(this);
        note.setText("video preview only for 30 seconds, portrait image preview may cut in preview");
        note.setTextColor(Color.LTGRAY);
        layout.addView(note);


        // ----- Buttons -----
        Button chooseLutBtn = new Button(this);
        chooseLutBtn.setText("Choose LUT");
        chooseLutBtn.setTextSize(18f);
        styleButton(chooseLutBtn, "#06B6D4");
        chooseLutBtn.setTextColor(Color.BLACK);
        layout.addView(chooseLutBtn);

        Button resetBtn = new Button(this);
        resetBtn.setText("Reset");
        styleButton(resetBtn, "#455A64"); // grey like other buttons
        layout.addView(resetBtn);

        resetBtn.setOnClickListener(v -> applyNoneLutAndNeutralGrade());

        Button pickFileButton = new Button(this);
        pickFileButton.setText("Load-Share Image or Video");
        pickFileButton.setTextSize(20f);
        styleButton(pickFileButton, "#BDBDBD");
        pickFileButton.setTextColor(Color.BLACK);
        layout.addView(pickFileButton);

        // ======= Effects bar =======
        TextView effectsTitle = new TextView(this);
        effectsTitle.setText("\nEffects");
        effectsTitle.setTextColor(Color.WHITE);
        effectsTitle.setTextSize(18f);
        layout.addView(effectsTitle);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        effectsRow = new LinearLayout(this);
        effectsRow.setOrientation(LinearLayout.HORIZONTAL);
        effectsRow.setPadding(0, dp(6), 0, dp(6));
        hsv.addView(effectsRow);
        layout.addView(hsv);

        for (Effect e : Effect.values()) layoutChip(effectsRow, makeEffectChip(e));

        // Dynamic controls
        controlsArea = new LinearLayout(this);
        controlsArea.setOrientation(LinearLayout.VERTICAL);
        controlsArea.setPadding(0, dp(6), 0, dp(10));
        layout.addView(controlsArea);
        renderControlsFor(selectedEffect);

        // Progress UI
        progressLabel = new TextView(this);
        progressLabel.setTextColor(Color.WHITE);
        progressLabel.setVisibility(INVISIBLE);
        progressLabel.setText("Exporting…");
        layout.addView(progressLabel);

        circularProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        circularProgressBar.setIndeterminate(false);
        circularProgressBar.setMax(100);
        circularProgressBar.setVisibility(View.GONE);
        layout.addView(circularProgressBar);

        progressText = new TextView(this);
        progressText.setTextSize(16f);
        progressText.setTextColor(Color.WHITE);
        progressText.setGravity(Gravity.CENTER);
        progressText.setVisibility(View.GONE);
        layout.addView(progressText);

        processButton = new Button(this);
        processButton.setText("Export");
        styleButton(processButton, "#607D8B");
        processButton.setTextSize(18f);
        processButton.setTextColor(Color.BLACK);
        processButton.setPadding(40, 20, 40, 20);
        layout.addView(processButton);

        Button backBtn = new Button(this);
        backBtn.setText("Back to Mode Selection");
        styleButton(backBtn, "#BDBDBD");
        backBtn.setTextColor(Color.BLACK);
        GradientDrawable backDrawable = new GradientDrawable();
        backDrawable.setCornerRadius(30f);
        backDrawable.setColor(Color.parseColor("#BDBDBD"));
        backBtn.setBackground(backDrawable);
        backBtn.setPadding(40, 30, 40, 30);
        LinearLayout.LayoutParams backBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        backBtnParams.topMargin = 40;
        backBtn.setLayoutParams(backBtnParams);
        layout.addView(backBtn);

        // Scroll wrapper
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

        // Wire buttons
        pickFileButton.setOnClickListener(v -> pickMediaFile());
        chooseLutBtn.setOnClickListener(v -> openLutDialog());
        processButton.setOnClickListener(v -> {
            if (selectedUri == null) {
                Toast.makeText(this, "Please select a video or image to process.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isImage) processImage(); else processVideo();
        });
        backBtn.setOnClickListener(v -> {
            Intent back = new Intent(this, MainActivity.class);
            back.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(back);
            finish();
        });

        if (selectedUri != null) updatePreview();
    }

    // ===== LUT Dialog =====
    private void openLutDialog() {
        String preselectAsset = (selectedLutId != null && selectedLutId.startsWith("asset:"))
                ? selectedLutId.substring("asset:".length())
                : null;

        LutPickerDialogFragment.show(
                getSupportFragmentManager(),
                "lut_picker",
                preselectAsset,
                isPurchased,
                new LutPickerDialogFragment.Listener() {
                    @Override public void onLutChosen(@Nullable String lutName) {
                        selectedLutId = (lutName == null) ? null : LutManager.toAssetId(lutName);
                        Toast.makeText(LUTPreviewActivity.this,
                                "LUT: " + LutManager.prettyTitle(lutName),
                                Toast.LENGTH_SHORT).show();
                        updatePreview();
                    }
                    @Override public void onExternalLutChosen(@NonNull Uri lutUri, @NonNull String displayName) {
                        // Persist content Uri to app files and use "file:" ID
                        try {
                            File out = new File(LutManager.getUserLutsDir(LUTPreviewActivity.this), displayName);
                            if (!displayName.toLowerCase(Locale.US).endsWith(".cube")) {
                                out = new File(out.getParentFile(), displayName + ".cube");
                            }
                            try (InputStream in = getContentResolver().openInputStream(lutUri);
                                 FileOutputStream fos = new FileOutputStream(out)) {
                                if (in == null) throw new IllegalStateException("Cannot open imported LUT");
                                byte[] buf = new byte[8192]; int n;
                                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                            }
                            selectedLutId = LutManager.toFileId(out);
                            Toast.makeText(LUTPreviewActivity.this, "Imported: " + out.getName(), Toast.LENGTH_SHORT).show();
                            updatePreview();
                        } catch (Exception e) {
                            Toast.makeText(LUTPreviewActivity.this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                    @Override public void onUpgradeRequested() {
                        try {
                            if (billingManager != null) billingManager.launchPurchaseFlow(LUTPreviewActivity.this);
                            else Toast.makeText(LUTPreviewActivity.this, "Billing not available", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Toast.makeText(LUTPreviewActivity.this, "Billing error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
        );
    }
    private void applyNoneLutAndNeutralGrade() {
        // 1) No LUT
        selectedLutId = null;              // or: "none"

        // 2) Neutral grade
        tint = 0f;
        contrast = 1f;
        saturation = 1f;

        exposure = 0f;
        vibrance = 0f;
        temp = 0f;
        greenMagenta = 0f;
        highlightRoll = 0f;
        vignetteStrength = 0f;
        vignetteSoftness = 0.8f; // harmless when strength==0

        // 3) Push to live preview if a session exists
        if (videoSession != null) {
            videoSession.setLUT(null);                 // explicit: no LUT
            videoSession.setGrade(tint, contrast, saturation);
            applyAdvancedParams(videoSession);
        }

        // 4) Rebuild sliders to reflect new values & redraw
        renderControlsFor(selectedEffect);
        updatePreview();

        Toast.makeText(this, "Reset: None LUT + Neutral grade", Toast.LENGTH_SHORT).show();
    }

    // ===== UI: effect chips & controls =====
    private void layoutChip(LinearLayout row, View chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, dp(6), 0);
        chip.setLayoutParams(lp);
        row.addView(chip);
    }
    private View makeEffectChip(Effect effect) {
        FrameLayout wrap = new FrameLayout(this);
        Button chip = new Button(this);
        chip.setAllCaps(false);
        chip.setText(effect.label);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(14f);
        chip.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF37474F);
        bg.setCornerRadius(dp(22));
        chip.setBackground(bg);
        chip.setTag(effect);
        chip.setOnClickListener(v -> { selectedEffect = (Effect) v.getTag(); highlightSelectedChip(); renderControlsFor(selectedEffect); });
        wrap.addView(chip);
        return wrap;
    }
    private void highlightSelectedChip() {
        for (int i = 0; i < effectsRow.getChildCount(); i++) {
            View w = effectsRow.getChildAt(i);
            if (!(w instanceof FrameLayout)) continue;
            FrameLayout fl = (FrameLayout) w; if (fl.getChildCount() == 0) continue;
            Button b = (Button) fl.getChildAt(0); Effect e = (Effect) b.getTag();
            GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(22));
            bg.setColor(e == selectedEffect ? 0xFF1E88E5 : 0xFF37474F); b.setBackground(bg);
        }
    }
    private void renderControlsFor(Effect effect) {
        controlsArea.removeAllViews();
        switch (effect) {
            case CONTRAST:
                controlsArea.addView(makeLabel("Contrast"));
                controlsArea.addView(makeSeek(0, 300, (int)(contrast * 100), v -> { contrast = v / 100f; updatePreview(); })); break;
            case SATURATION:
                controlsArea.addView(makeLabel("Saturation"));
                controlsArea.addView(makeSeek(0, 300, (int)(saturation * 100), v -> { saturation = v / 100f; updatePreview(); })); break;
            case TINT:

                controlsArea.addView(makeLabel("Tint / Hue Bias"));
                // Slider range -100..+100 maps directly to tint -1.00..+1.00
                controlsArea.addView(makeSeek(
                        -100, 100, Math.round(tint * 100f),      // 0 == neutral on load when tint == 0f
                        v -> { tint = v / 100f; updatePreview(); }
                ));
                break;
            case EXPOSURE:
                controlsArea.addView(makeLabel("Exposure (EV)"));
                controlsArea.addView(makeSeek(-300, 300, (int)(exposure * 100), v -> { exposure = v / 100f; updatePreview(); })); break;
            case VIBRANCE:
                controlsArea.addView(makeLabel("Vibrance"));
                controlsArea.addView(makeSeek(-100, 100, (int)(vibrance * 100), v -> { vibrance = v / 100f; updatePreview(); })); break;
            case TEMP_TINT:
                controlsArea.addView(makeLabel("Temperature (Warm/Cool)"));
                controlsArea.addView(makeSeek(-100, 100, (int)(temp * 100), v -> { temp = v / 100f; updatePreview(); }));
                controlsArea.addView(makeLabel("Tint (Green ↔ Magenta)"));
                controlsArea.addView(makeSeek(-100, 100, (int)(greenMagenta * 100), v -> { greenMagenta = v / 100f; updatePreview(); })); break;
            case HIGHLIGHT_ROLL:
                controlsArea.addView(makeLabel("Highlight Roll-off"));
                controlsArea.addView(makeSeek(0, 100, (int)(highlightRoll * 100), v -> { highlightRoll = v / 100f; updatePreview(); })); break;
            case VIGNETTE:
                controlsArea.addView(makeLabel("Vignette Strength"));
                controlsArea.addView(makeSeek(0, 100, (int)(vignetteStrength * 100), v -> { vignetteStrength = v / 100f; updatePreview(); }));
                controlsArea.addView(makeLabel("Vignette Softness"));
                controlsArea.addView(makeSeek(10, 100, (int)(vignetteSoftness * 100), v -> { vignetteSoftness = v / 100f; updatePreview(); })); break;
        }
        controlsArea.addView(makeDivider());
        highlightSelectedChip();
    }

    // ===== styling helpers =====
    private void styleButton(Button button, String hexColor) {
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16f);
        button.setPadding(30, 30, 30, 30);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(40f);
        button.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 30, 0, 0);
        button.setLayoutParams(params);
    }
    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(Color.WHITE); tv.setTextSize(16f);
        tv.setPadding(0, dp(6), 0, dp(2)); return tv;
    }
    private interface OnSeek { void onChanged(int value); }
    private View makeSeek(int min, int max, int value, OnSeek cb) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        SeekBar sb = new SeekBar(this); sb.setMax(max - min); sb.setProgress(value - min); sb.setPadding(0, 0, 0, dp(4));
        TextView val = new TextView(this); val.setTextColor(Color.LTGRAY); val.setTextSize(12f); val.setText(String.valueOf(value / 100f));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) { int actual = p + min; val.setText(String.valueOf(actual / 100f)); cb.onChanged(actual); }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(sb); box.addView(val); return box;
    }
    private View makeDivider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(8), 0, dp(8)); v.setLayoutParams(lp); v.setBackgroundColor(0x33FFFFFF); return v;
    }
    private int dp(int v) { float d = getResources().getDisplayMetrics().density; return Math.round(d * v); }

    // ===== Permissions / lifecycle =====
    private void checkPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        }
    }
    @Override protected void onDestroy() { if (videoSession != null) { videoSession.release(); videoSession = null; } super.onDestroy(); }

    // ===== Media pickers =====
    private void pickMediaFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, "Pick file"), REQUEST_CODE_PICK_FILE);
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent d) {
        super.onActivityResult(requestCode, resultCode, d);
        if (resultCode != RESULT_OK || d == null) return;

        if (requestCode == REQUEST_CODE_PICK_FILE) {
            selectedUri = d.getData();
            if (selectedUri == null) {
                Log.e("onActivityResult", "❌ No URI received.");
                Toast.makeText(this, "Failed to get file URI", Toast.LENGTH_LONG).show();
                return;
            }
            String type = null; try { type = getContentResolver().getType(selectedUri); } catch (Exception ignored) {}
            if (type != null) {
                isImage = type.startsWith("image/");
                if (!isImage) isImage = false;

            } else {
                String path = selectedUri.toString().toLowerCase();
                isImage = path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp");
                if (!isImage) isImage = false;

            }
            updatePreview();
            return;
        }
        // (Legacy import flow kept, but dialog now handles import itself)
        if (requestCode == REQUEST_CODE_IMPORT_LUT) {
            Uri uri = d.getData();
            if (uri == null) return;
            try {
                String displayName = queryDisplayName(uri);
                if (displayName == null) displayName = "Imported_" + System.currentTimeMillis() + ".cube";
                if (!displayName.toLowerCase().endsWith(".cube")) displayName = displayName + ".cube";
                File out = new File(LutManager.getUserLutsDir(this), displayName);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    if (in == null) throw new IllegalStateException("Cannot open imported LUT");
                    byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                }
                selectedLutId = LutManager.toFileId(out);
                Toast.makeText(this, "Imported: " + displayName, Toast.LENGTH_SHORT).show();
                updatePreview();
            } catch (Exception e) {
                Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
    private String queryDisplayName(Uri uri) {
        String result = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ===== Preview / Export =====
    private String legacyLutArg(String lutId) {
        if (lutId == null) return null;
        if (lutId.startsWith("asset:")) return lutId.substring("asset:".length());
        if (lutId.startsWith("file:"))  return lutId.substring("file:".length());
        return lutId;
    }
    private void applyAdvancedParams(LUTPreviewHelper.Session s) {
        if (s == null) return;
        try { s.setExposure(exposure); } catch (Throwable ignored) {}
        try { s.setVibrance(vibrance); } catch (Throwable ignored) {}
        try { s.setTempTint(temp, greenMagenta); } catch (Throwable ignored) {}
        try { s.setHighlightRoll(highlightRoll); } catch (Throwable ignored) {}
        try { s.setVignette(vignetteStrength, vignetteSoftness); } catch (Throwable ignored) {}
    }
    private void updatePreview() {
        if (selectedUri == null) return;
        if (previewRunnable != null) previewHandler.removeCallbacks(previewRunnable);

        // Track image changes only (do not affect video)
        if (isImage) {
            if (lastImageUri == null || !selectedUri.equals(lastImageUri)) {
                lastImageUri = selectedUri;
                didAutoNeutralizeForImage = false;
            }
        }

        final String lutForLegacy = legacyLutArg(selectedLutId);

        previewRunnable = () -> {
            if (isImage) {
                // One-time neutral reset for this image
                if (!didAutoNeutralizeForImage) {
                    didAutoNeutralizeForImage = true;           // set first to avoid loops
                    applyNoneLutAndNeutralGrade(/*silent=*/true);
                    return;                                      // let the re-invoked call render neutrally
                }

                // ---- IMAGE PREVIEW ----
                previewContainer.removeAllViews();
                Bitmap bmp = LUTImageProcessor.getPreviewBitmap(
                        this, selectedUri, lutForLegacy, contrast, saturation, tint);
                if (bmp != null) previewImage.setImageBitmap(bmp);
                previewContainer.addView(previewImage);
                if (videoSession != null) { videoSession.release(); videoSession = null; }
            } else {
                // ---- VIDEO PREVIEW (unchanged) ----
                if (videoSession == null) {
                    previewContainer.removeAllViews();
                    videoSession = LUTPreviewHelper.showPreview(
                            this, selectedUri, selectedLutId, previewContainer,
                            /*mute=*/true, tint, contrast, saturation, /*loop=*/true
                    );
                    applyAdvancedParams(videoSession);
                } else {
                    videoSession.setGrade(tint, contrast, saturation);
                    videoSession.setLUT(selectedLutId);
                    applyAdvancedParams(videoSession);
                    if (previewContainer.getChildCount() == 0
                            || previewContainer.getChildAt(0) != videoSession.glView) {
                        previewContainer.removeAllViews();
                        previewContainer.addView(videoSession.glView);
                    }
                }
            }
        };

        previewHandler.postDelayed(previewRunnable, 120);
    }

    private void applyNoneLutAndNeutralGrade(boolean silent) {
        selectedLutId = null;
        tint = 0f; contrast = 1f; saturation = 1f;
        exposure = 0f; vibrance = 0f; temp = 0f; greenMagenta = 0f;
        highlightRoll = 0f; vignetteStrength = 0f; vignetteSoftness = 0f;
        if (!silent) Toast.makeText(this, "Reset to Neutral", Toast.LENGTH_SHORT).show();
        updatePreview();
    }


    private void processVideo() {
        if (isExporting) return;
        isExporting = true;
        LUTProcessor.resetState();
        try {
            if (selectedUri == null) {
                Toast.makeText(this, "Please select a video first.", Toast.LENGTH_LONG).show();
                isExporting = false;
                return;
            }

            runOnUiThread(() -> {
                circularProgressBar.setProgress(0);
                progressText.setText("0%");
                circularProgressBar.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                progressLabel.setVisibility(View.VISIBLE);
            });

            new Thread(() -> {
                Uri videoUri = null;
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "lut_video_" + timestamp() + ".mp4");
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/LUTProcessed");
                    videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (videoUri == null) throw new Exception("Failed to create MediaStore entry for video");

                    File tempFile = new File(getCacheDir(), "temp_lut_output_" + System.currentTimeMillis() + ".mp4");

                    // Map UI absolutes (neutral=1.0) to exporter deltas (exporter uses 1f + delta)
                    float contrastDelta   = contrast   - 1f;  // e.g., 1.20 -> 0.20
                    float saturationDelta = saturation - 1f;  // e.g., 1.10 -> 0.10
                    float tintDelta       = tint;             // already a delta (neutral = 0.0)

                    // Advanced export: matches preview (vignette/exposure/temp/tintGM/highlightRoll all applied)
                    LUTProcessor processor = new LUTProcessor();
                    processor.processAdvanced(
                            this,
                            selectedUri,
                            selectedLutId,            // null => no LUT applied
                            tempFile,
                            tintDelta,
                            contrastDelta,
                            saturationDelta,
                            exposure,
                            vibrance,
                            temp,
                            greenMagenta,
                            highlightRoll,
                            vignetteStrength,
                            vignetteSoftness,
                            circularProgressBar, progressText, progressLabel
                    );

                    try (OutputStream out = getContentResolver().openOutputStream(videoUri);
                         FileInputStream in = new FileInputStream(tempFile)) {
                        if (out == null) throw new IllegalStateException("Cannot open output stream");
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
                    }
                } catch (Exception e) {
                    Uri finalUri = videoUri;
                    runOnUiThread(() -> {
                        if (finalUri != null) getContentResolver().delete(finalUri, null, null);
                        Toast.makeText(this, "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                } finally {
                    runOnUiThread(() -> {
                        circularProgressBar.setVisibility(View.GONE);
                        progressText.setVisibility(View.GONE);
                        progressLabel.setVisibility(View.GONE);
                        isExporting = false;
                    });
                    System.gc();
                }
            }).start();
        } catch (Exception e) {
            Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            isExporting = false;
        }
    }


    private void processImage() {
        if (isExporting) return;
        isExporting = true;

        new Thread(() -> {
            runOnUiThread(() -> {
                circularProgressBar.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                progressLabel.setVisibility(View.VISIBLE);
            });

            OutputStream out = null;
            Uri imageUri = null;

            try {
                // Create MediaStore entry
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "processed_" + timestamp() + ".jpg");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/LUTProcessed");
                imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (imageUri == null) throw new Exception("Failed to create MediaStore entry");

                out = getContentResolver().openOutputStream(imageUri);
                if (out == null) throw new IllegalStateException("Cannot open output stream");

                // Map UI absolutes (neutral = 1.0) to deltas (exporter uses 1f + delta)
                float contrastDelta   = contrast   - 1f;  // e.g., 1.20 -> 0.20
                float saturationDelta = saturation - 1f;  // e.g., 1.10 -> 0.10
                float tintDelta       = tint;             // already centered at 0.0

                // Try advanced image export via reflection (if available)
                try {
                    java.lang.reflect.Method m = LUTImageProcessor.class.getMethod(
                            "processAdvanced",
                            android.content.Context.class, Uri.class, String.class, OutputStream.class,
                            float.class, float.class, float.class, // tint, contrastΔ, saturationΔ
                            float.class, float.class,              // exposure, vibrance
                            float.class, float.class,              // temp, greenMagenta
                            float.class,                           // highlightRoll
                            float.class, float.class               // vignetteStrength, vignetteSoftness
                    );

                    // Call advanced path (matches video export & preview)
                    m.invoke(
                            null,
                            this,
                            selectedUri,
                            selectedLutId,     // may be null => no LUT
                            out,
                            tintDelta,
                            contrastDelta,
                            saturationDelta,
                            exposure,
                            vibrance,
                            temp,
                            greenMagenta,
                            highlightRoll,
                            vignetteStrength,
                            vignetteSoftness
                    );

                } catch (NoSuchMethodException nsme) {
                    // Fallback to legacy/simple image export (absolute values)
                    // NOTE: This path will NOT include exposure/temp/vignette/etc.
                    LUTImageProcessor.process(
                            this,
                            selectedUri,
                            selectedLutId, // may be null
                            out,
                            contrast,       // absolute (1.0 neutral)
                            saturation,     // absolute (1.0 neutral)
                            tint            // centered (0.0 neutral)
                    );
                }

                runOnUiThread(() -> Toast.makeText(this, "✅ Image Saved!", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                // Cleanup broken entry on error
                if (imageUri != null) {
                    try { getContentResolver().delete(imageUri, null, null); } catch (Throwable ignored) {}
                }
                final String msg = e.getMessage();
                runOnUiThread(() -> Toast.makeText(this, "❌ " + (msg != null ? msg : "Export failed"), Toast.LENGTH_LONG).show());

            } finally {
                try { if (out != null) out.close(); } catch (Throwable ignored) {}

                runOnUiThread(() -> {
                    circularProgressBar.setVisibility(View.GONE);
                    progressText.setVisibility(View.GONE);
                    progressLabel.setVisibility(View.GONE);
                });

                isExporting = false;

                // refresh preview after a short delay
                runOnUiThread(() -> new android.os.Handler().postDelayed(this::updatePreview, 300));
                System.gc();
            }
        }).start();
    }


    private String timestamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }
}
