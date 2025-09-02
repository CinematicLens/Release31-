// File: LutPickerDialogFragment.java
package com.squeezer.app;

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class LutPickerDialogFragment extends DialogFragment {

    public interface Listener {
        void onLutChosen(@Nullable String lutName); // asset relative path ("Free/Foo.cube", "Pro/Bar.cube") or null for None
        default void onExternalLutChosen(@NonNull Uri lutUri, @NonNull String displayName) {}
        void onUpgradeRequested();
    }
    private boolean didDispatchInitialApply = false;
    private static final String ARG_SELECTED = "arg_selected";
    private static final String ARG_IS_PRO   = "arg_is_pro";
    private static final String NONE_LABEL   = "None (no LUT)";
    private static final String IMPORT_LABEL = "➕ Import LUT…";

    private Listener listener;

    public static void show(@NonNull FragmentManager fm,
                            @NonNull String tag,
                            @Nullable String selectedLut /* e.g., "Free/Foo.cube", "Pro/Bar.cube", or plain "Foo.cube" / "none" */,
                            boolean isProUser,
                            @NonNull Listener listener) {
        LutPickerDialogFragment f = new LutPickerDialogFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED, selectedLut);
        b.putBoolean(ARG_IS_PRO, isProUser);
        f.setArguments(b);
        f.setListener(listener);
        f.show(fm, tag);
    }

    public void setListener(@NonNull Listener l) { this.listener = l; }

    // ---- UI ----
    private ImageView preview;
    private LinearLayout categoryRow;  // "Free" | "Pro" buttons
    private LinearLayout chipRow;
    private Button applyBtn;

    // ---- State ----
    private boolean isPro;                         // single source of truth for entitlement
    private String preselectedAssetName;           // normalized asset name, or null when “none”
    private ViewRef viewing;                       // current preview target

    // Grouped assets: key = "Free" | "Pro"; values = relative paths like "Free/X.cube" or "Pro/Y.cube"
    private final Map<String, List<String>> groups = new LinkedHashMap<>();
    private String currentCategory = "Free";

    // Imported (session-local)
    private final List<ViewRef> imported = new ArrayList<>();

    // ---- Preview infra ----
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private Bitmap baseBitmapSmall;
    private final Map<String, Bitmap> previewCache = new LinkedHashMap<String, Bitmap>(16,0.75f,true){
        @Override protected boolean removeEldestEntry(Entry<String, Bitmap> e){ return size() > 12; }
    };
    private final Map<String, CubeLut> lutCache = new LinkedHashMap<String, CubeLut>(16,0.75f,true){
        @Override protected boolean removeEldestEntry(Entry<String, CubeLut> e){ return size() > 8; }
    };

    private ActivityResultLauncher<String[]> openDoc;

    // Represents None, an asset LUT, or an imported URI LUT.
    private static final class ViewRef {
        enum Kind { NONE, ASSET, EXTERNAL }
        final Kind kind;
        final @Nullable String assetName; // e.g., "Free/Foo.cube" or "Pro/Bar.cube"
        final @Nullable Uri uri;
        final @NonNull String display;
        ViewRef(Kind k, @Nullable String asset, @Nullable Uri u, @NonNull String label){
            this.kind=k; this.assetName=asset; this.uri=u; this.display=label;
        }
        String cacheKey(){ return kind==Kind.NONE? "none" : (kind==Kind.ASSET? "asset:"+assetName : "uri:"+uri); }
    }
    private void dispatchInitialIfNone() {
        if (didDispatchInitialApply) return;
        if (viewing != null && viewing.kind == ViewRef.Kind.NONE) {
            didDispatchInitialApply = true;
            // post to main so host is fully attached
            main.post(() -> {
                if (listener != null) listener.onLutChosen(null); // <- APPLY NONE
            });
        }
    }
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        // entitlement
        isPro = (getArguments()!=null) && getArguments().getBoolean(ARG_IS_PRO, false);

        // selected LUT, sanitize "none"/empty → null (None)
        String incoming = (getArguments()!=null) ? getArguments().getString(ARG_SELECTED) : null;
        if (incoming != null) {
            String s = incoming.trim();
            if (s.isEmpty() || "none".equalsIgnoreCase(s)) incoming = null;
        }
        preselectedAssetName = incoming;

        // SAF picker
        openDoc = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onExternalPicked);

        // Scan assets: luts/Free (or "LUT Free") and luts/Pro
        scanGroupedLuts(ctx.getAssets());

        // Decide initial category
        if (preselectedAssetName != null) {
            String norm = preselectedAssetName.replace('\\','/').toLowerCase(Locale.US);
            currentCategory = norm.startsWith("pro/") ? "Pro" : "Free";
        } else if (!groups.containsKey("Free") && groups.containsKey("Pro")) {
            currentCategory = "Pro";
        } else {
            currentCategory = "Free";
        }

        // Build UI
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText(isPro ? "🎞 LUTs" : "🎞 LUTs • 👑 Pro");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(12);
        root.addView(title, tlp);

        // Preview
        preview = new ImageView(ctx);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
        plp.bottomMargin = dp(12);
        root.addView(preview, plp);

        // Category buttons
        categoryRow = new LinearLayout(ctx);
        categoryRow.setOrientation(LinearLayout.HORIZONTAL);
        categoryRow.setPadding(0, 0, 0, dp(8));
        buildCategoryRow(); // adds "Free" and/or "Pro" buttons
        root.addView(categoryRow);

        // Chips row
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, 0, 0, 0);
        hsv.addView(chipRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(hsv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Initial viewing target
        viewing = (preselectedAssetName == null)
                ? new ViewRef(ViewRef.Kind.NONE, null, null, NONE_LABEL)
                : new ViewRef(ViewRef.Kind.ASSET, normalizeIncoming(preselectedAssetName), null, displayName(preselectedAssetName));

        // Build chips for current category
        rebuildChipRow();

        // Load preview base bitmap, then apply current selection
        loadBasePreviewBitmapThenApply(viewing);
        dispatchInitialIfNone();

        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", null)
                .create();

        dlg.setOnShowListener(d -> {
            applyBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            updateApplyButtonText();
            applyBtn.setOnClickListener(v -> {
                switch (viewing.kind) {
                    case NONE:
                        if (listener != null) listener.onLutChosen(null); // None stays in-app, no navigation
                        dismissAllowingStateLoss();
                        return;
                    case ASSET: {
                        boolean wantsPro = isProAsset(viewing.assetName);
                        if (wantsPro && !isPro) { if (listener != null) listener.onUpgradeRequested(); return; }
                        if (listener != null) listener.onLutChosen(viewing.assetName);
                        dismissAllowingStateLoss();
                        return;
                    }
                    case EXTERNAL:
                        if (!isPro) { if (listener != null) listener.onUpgradeRequested(); return; }
                        if (listener != null) {
                            String label = viewing.display != null ? viewing.display : "Custom LUT";
                            listener.onExternalLutChosen(viewing.uri, label);
                        }
                        dismissAllowingStateLoss();
                        return;
                }
            });
        });

        return dlg;
    }

    // --------- Category & chip building ---------

    private void buildCategoryRow() {
        categoryRow.removeAllViews();

        if (groups.containsKey("Free")) {
            categoryRow.addView(makeCategoryButton("Free"));
        }
        if (groups.containsKey("Pro")) {
            categoryRow.addView(makeCategoryButton("Pro"));
        }
        highlightCategoryButtons();
    }

    private View makeCategoryButton(String label) {
        Button b = new Button(requireContext());
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14f);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xFF455A64);
        b.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);

        b.setOnClickListener(v -> {
            if ("Pro".equals(label) && !isPro) {
                Toast.makeText(requireContext(),
                        "Pro LUT pack requires a premium upgrade.",
                        Toast.LENGTH_SHORT).show();
                return; // don't switch category for non-Pro users
            }
            currentCategory = label;
            highlightCategoryButtons();
            rebuildChipRow();
        });
        b.setTag("cat:" + label);
        return b;
    }

    private void highlightCategoryButtons() {
        for (int i = 0; i < categoryRow.getChildCount(); i++) {
            View v = categoryRow.getChildAt(i);
            if (!(v instanceof Button)) continue;
            Button b = (Button) v;
            String tag = String.valueOf(b.getTag());
            boolean sel = tag.equals("cat:" + currentCategory);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(sel ? 0xFF1E88E5 : 0xFF455A64);
            b.setBackground(bg);
        }
    }

    private void rebuildChipRow() {
        chipRow.removeAllViews();

        // Import & None chips always first
        addChip(new ViewRef(ViewRef.Kind.EXTERNAL, null, null, IMPORT_LABEL), /*locked*/ false, /*importChip*/ true);
        addChip(new ViewRef(ViewRef.Kind.NONE, null, null, NONE_LABEL), false, false);

        // Group items
        List<String> names = groups.get(currentCategory);
        if (names != null) {
            // sort by pretty display name
            names.sort(Comparator.comparing(this::displayName, String::compareToIgnoreCase));
            for (String rel : names) {
                boolean locked = isProAsset(rel) && !isPro;
                addChip(new ViewRef(ViewRef.Kind.ASSET, rel, null, displayName(rel)), locked, false);
            }
        }

        // Re-highlight if viewing matches a chip in current row
        highlightSelectionInRow();
        updateApplyButtonText();
    }

    private String displayName(String pathOrName) {
        String s = pathOrName;
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return s;
    }

    private String normalizeIncoming(String name) {
        // If caller passed plain "Foo.cube", map it to the first group that contains it.
        if (name == null) return null;
        if (name.contains("/")) return name;
        String plainLower = name.toLowerCase(Locale.US);
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            for (String rel : e.getValue()) {
                if (displayName(rel).toLowerCase(Locale.US).equals(plainLower)) return rel;
            }
        }
        // default to Free/<name>
        return "Free/" + name;
    }

    // --------- Chips ---------

    private void addChip(ViewRef ref, boolean locked, boolean importChip) {
        final Context ctx = requireContext();
        TextView chip = new TextView(ctx);
        chip.setText(ref.display + (locked && !importChip ? "  👑" : ""));
        chip.setTextSize(13);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setTextColor(locked ? 0xCCFFFFFF : 0xFFFFFFFF);
        chip.setBackground(makeChipBg(importChip ? 0xFF2962FF : (ref.kind==ViewRef.Kind.NONE ? 0xFF4CAF50 : 0xFF444444), dp(16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setAlpha(locked && !importChip ? 0.65f : 1f);

        if (importChip) {
            chip.setOnClickListener(v -> {
                if (!isPro) { // gate import on Pro only
                    Toast.makeText(requireContext(),
                            "Importing LUTs is a premium feature.",
                            Toast.LENGTH_SHORT).show();
                    return; // don't open picker
                }
                openDoc.launch(new String[]{"*/*"});
            });
            chip.setTag("chip_import");
        } else {
            chip.setOnClickListener(v -> {
                viewing = ref;         // preview even if locked
                highlightSelectionInRow();
                updateApplyButtonText();
                applyPreviewAsync(viewing);
            });
            chip.setTag(tagFor(ref));
        }
        chipRow.addView(chip);
    }

    private static String tagFor(ViewRef r){
        switch (r.kind){
            case NONE: return "chip_none";
            case ASSET: return "chip_asset:" + r.assetName;
            default: return "chip_uri:" + String.valueOf(r.uri);
        }
    }

    private void highlightSelectionInRow() {
        int n = chipRow.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = chipRow.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView tv = (TextView) v;
            Object tag = tv.getTag();
            boolean isSelected = tag != null && tag.equals(tagFor(viewing));
            tv.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            boolean locked = false;
            if (tag instanceof String) {
                String s = (String) tag;
                if (s.startsWith("chip_asset:")) {
                    String assetName = s.substring("chip_asset:".length());
                    locked = !isPro && isProAsset(assetName);
                }
            }

            tv.setBackground(makeChipBg(
                    isSelected ? 0xFF00C853
                            : (locked ? 0xFF444444 : 0xFF555555),
                    dp(16)));
        }
    }

    private boolean isProAsset(@Nullable String assetName) {
        if (assetName == null) return false;
        String s = assetName.replace('\\','/').toLowerCase(Locale.US);
        return s.startsWith("pro/");
    }

    private void updateApplyButtonText() {
        if (applyBtn == null) return;
        switch (viewing.kind){
            case NONE:
                applyBtn.setText("Apply (None)"); return;
            case ASSET:
                boolean wantsPro = isProAsset(viewing.assetName);
                applyBtn.setText((wantsPro && !isPro) ? "Unlock Pro to Apply" : "Apply");
                return;
            case EXTERNAL:
                applyBtn.setText(isPro ? "Apply" : "Unlock Pro to Apply");
        }
    }

    // ----- SAF result -----
    private void onExternalPicked(@Nullable Uri uri) {
        if (uri == null) return;

        final Context appCtx = getContext() != null ? getContext().getApplicationContext() : null;
        if (appCtx == null) return;

        String name = queryDisplayName(appCtx.getContentResolver(), uri);
        if (name == null) name = "custom.cube";
        if (!name.toLowerCase(Locale.US).endsWith(".cube")) {
            Toast.makeText(appCtx, "Please pick a .cube file", Toast.LENGTH_SHORT).show();
            return;
        }

        final String finalName = name;
        exec.submit(() -> {
            ValidationResult vr;
            try (InputStream is = appCtx.getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("Cannot open file");
                vr = validateCubeLut(is, /*strict*/true);
            } catch (Throwable t) {
                vr = ValidationResult.error("Failed to read LUT: " + t.getMessage());
            }
            final ValidationResult res = vr;
            main.post(() -> {
                if (!res.ok) { Toast.makeText(appCtx, res.message, Toast.LENGTH_LONG).show(); return; }
                ViewRef ref = new ViewRef(ViewRef.Kind.EXTERNAL, null, uri, finalName);
                imported.add(ref);
                // Show in current row (as additional chip)
                addChip(ref, !isPro, false);
                viewing = ref;
                highlightSelectionInRow();
                updateApplyButtonText();
                applyPreviewAsync(viewing);
            });
        });
    }

    private static @Nullable String queryDisplayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // ----- Preview pipeline (CPU preview only) -----
    private void loadBasePreviewBitmapThenApply(ViewRef target) {
        exec.submit(() -> {
            Bitmap src = null;
            try (InputStream is = requireContext().getAssets().open("luts/preview.jpg")) {
                Bitmap full = BitmapFactory.decodeStream(is);
                src = scaleForPreview(full, 320);
                if (full != null && full != src) full.recycle();
            } catch (Throwable ignore) {}
            if (src == null) src = makeGradientPreview(320, 200);
            baseBitmapSmall = src;
            main.post(() -> {
                preview.setImageBitmap(baseBitmapSmall);
                highlightSelectionInRow();
                applyPreviewAsync(target);
            });
        });
    }

    private void applyPreviewAsync(ViewRef ref) {
        if (preview == null || baseBitmapSmall == null) return;

        // Use an app context (safe off the main thread & after fragment detaches)
        final android.content.Context appCtx =
                preview.getContext() != null
                        ? preview.getContext().getApplicationContext()
                        : null;
        if (appCtx == null) return;

        if (ref.kind == ViewRef.Kind.NONE) {
            preview.setImageBitmap(baseBitmapSmall);
            return;
        }

        String key = ref.cacheKey();
        Bitmap cached = previewCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            preview.setImageBitmap(cached);
            return;
        }

        preview.setImageBitmap(baseBitmapSmall);

        exec.submit(() -> {
            try {
                // NOTE: use appCtx, never requireContext() here
                CubeLut lut = (ref.kind == ViewRef.Kind.ASSET)
                        ? loadCubeFromAssets(appCtx, ref.assetName)
                        : loadCubeFromUri(appCtx, ref.uri);

                Bitmap out = applyLutToBitmap(baseBitmapSmall, lut);
                previewCache.put(key, out);

                main.post(() -> {
                    if (preview != null && out != null && !out.isRecycled()) {
                        preview.setImageBitmap(out);
                    }
                });

            } catch (Throwable t) {
                android.util.Log.e("LUTPreview", "Preview failed for key=" + key, t);
                main.post(() -> {
                    if (preview != null) preview.setImageBitmap(baseBitmapSmall);
                    android.widget.Toast.makeText(appCtx,
                            "Preview failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName() ),
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ----- Asset scanning -----
    private void scanGroupedLuts(AssetManager am) {
        groups.clear();

        // Helper to add a folder to a display group name
        addFolderIfAny(am, "Free", "Free");
        addFolderIfAny(am, "Free", "LUT Free"); // alias
        addFolderIfAny(am, "Pro",  "Pro");

        // Fallback: any .cube directly in luts/ → treat as Free
        if (!groups.containsKey("Free")) {
            try {
                String[] all = am.list("luts");
                if (all != null) {
                    List<String> flat = new ArrayList<>();
                    for (String f : all) {
                        if (f.toLowerCase(Locale.US).endsWith(".cube")) {
                            flat.add("Free/" + f); // display as Free
                        }
                    }
                    if (!flat.isEmpty()) groups.put("Free", flat);
                }
            } catch (IOException ignore) {}
        }

        // Ensure stable order
        if (groups.containsKey("Free")) Collections.sort(groups.get("Free"), String.CASE_INSENSITIVE_ORDER);
        if (groups.containsKey("Pro"))  Collections.sort(groups.get("Pro"), String.CASE_INSENSITIVE_ORDER);
    }

    private void addFolderIfAny(AssetManager am, String displayGroup, String folderName) {
        try {
            String base = "luts/" + folderName;
            String[] files = am.list(base);
            if (files == null || files.length == 0) return;
            List<String> list = groups.computeIfAbsent(displayGroup, k -> new ArrayList<>());
            for (String f : files) {
                if (f.toLowerCase(Locale.US).endsWith(".cube")) {
                    list.add(folderName + "/" + f); // keep real path, display uses last segment
                }
            }
        } catch (IOException ignore) {}
    }

    // ----- Lightweight CPU LUT for preview -----
    private static class CubeLut { final int size; final float[] data; CubeLut(int s, float[] d){ size=s; data=d; } }

    private static final class ValidationResult {
        final boolean ok; final @NonNull String message; final int size; final int entries;
        ValidationResult(boolean ok, @NonNull String msg, int size, int entries){ this.ok=ok; this.message=msg; this.size=size; this.entries=entries; }
        static ValidationResult ok(int size, int entries){ return new ValidationResult(true, "OK", size, entries); }
        static ValidationResult error(String msg){ return new ValidationResult(false, msg, 0, 0); }
    }

    private static ValidationResult validateCubeLut(InputStream in, boolean strict) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            int size1D = -1, size3D = -1;
            int rows1D = 0, rows3D = 0;
            int mode = 0; // 0=none, 1=1D, 3=3D

            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (isCommentLine(s)) continue;

                if (startsWithWordCI(s, "TITLE")) continue;
                if (startsWithWordCI(s, "DOMAIN_MIN")) continue;
                if (startsWithWordCI(s, "DOMAIN_MAX")) continue;

                if (startsWithWordCI(s, "LUT_3D_SIZE")) {
                    String[] p = s.substring(12).trim().split("\\s+");
                    if (p.length >= 1) size3D = Integer.parseInt(p[0]);
                    mode = 3;
                    continue;
                }
                if (startsWithWordCI(s, "LUT_1D_SIZE")) {
                    String[] p = s.substring(12).trim().split("\\s+");
                    if (p.length >= 1) size1D = Integer.parseInt(p[0]);
                    mode = 1;
                    continue;
                }

                // numeric row
                char c0 = s.charAt(0);
                if (Character.isDigit(c0) || c0=='-' || c0=='+') {
                    String[] p = s.split("\\s+");
                    if (p.length < 3) return ValidationResult.error("RGB row malformed");
                    // parse to verify numbers
                    Float.parseFloat(p[0]); Float.parseFloat(p[1]); Float.parseFloat(p[2]);
                    if (mode == 3) rows3D++;
                    else if (mode == 1) rows1D++;
                }
            }

            if (size3D > 0) {
                if (strict) {
                    int expected = size3D*size3D*size3D;
                    if (rows3D != expected) {
                        return ValidationResult.error("RGB count mismatch. expected " + expected + " rows, got " + rows3D);
                    }
                }
                return ValidationResult.ok(size3D, rows3D);
            }
            if (size1D > 0) {
                if (strict) {
                    if (rows1D != size1D) {
                        return ValidationResult.error("1D RGB count mismatch. expected " + size1D + " rows, got " + rows1D);
                    }
                }
                return ValidationResult.ok(size1D, rows1D);
            }
            return ValidationResult.error("Missing LUT_3D_SIZE or LUT_1D_SIZE");
        } catch (Throwable t) {
            return ValidationResult.error(t.getMessage());
        }
    }

    private CubeLut loadCubeFromAssets(Context ctx, String relPath) throws IOException {
        CubeLut hit = lutCache.get("asset:"+relPath); if (hit != null) return hit;
        InputStream is = ctx.getAssets().open("luts/" + relPath);
        ValidationResult vr = validateCubeLut(is, /*strict*/false); is.close();
        if (!vr.ok) throw new IOException("Invalid LUT ("+relPath+"): " + vr.message);
        is = ctx.getAssets().open("luts/" + relPath);
        CubeLut lut = parseCube(is, relPath);
        lutCache.put("asset:"+relPath, lut);
        return lut;
    }

    private CubeLut loadCubeFromUri(Context ctx, Uri uri) throws IOException {
        String key = "uri:" + uri; CubeLut hit = lutCache.get(key); if (hit != null) return hit;
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open LUT");
        ValidationResult vr = validateCubeLut(is, /*strict*/false); is.close();
        if (!vr.ok) throw new IOException("Invalid LUT: " + vr.message);
        is = ctx.getContentResolver().openInputStream(uri); if (is == null) throw new IOException("Cannot re-open LUT");
        CubeLut lut = parseCube(is, String.valueOf(uri)); lutCache.put(key, lut); return lut;
    }

    private static CubeLut parseCube(InputStream inputStream, String debugName) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        int size1D = -1, size3D = -1;
        int mode = 0; // 0=none, 1=1D, 3=3D
        ArrayList<float[]> data1D = new ArrayList<>();
        ArrayList<float[]> data3D = new ArrayList<>();

        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (isCommentLine(s)) continue;

            if (startsWithWordCI(s, "TITLE")) continue;
            if (startsWithWordCI(s, "DOMAIN_MIN")) continue;
            if (startsWithWordCI(s, "DOMAIN_MAX")) continue;

            if (startsWithWordCI(s, "LUT_3D_SIZE")) {
                String[] p = s.substring(12).trim().split("\\s+");
                if (p.length >= 1) size3D = Integer.parseInt(p[0]);
                mode = 3;
                continue;
            }
            if (startsWithWordCI(s, "LUT_1D_SIZE")) {
                String[] p = s.substring(12).trim().split("\\s+");
                if (p.length >= 1) size1D = Integer.parseInt(p[0]);
                mode = 1;
                continue;
            }

            // numeric
            char c0 = s.charAt(0);
            if (Character.isDigit(c0) || c0=='-' || c0=='+') {
                String[] p = s.split("\\s+");
                if (p.length < 3) throw new IOException("RGB row malformed");
                float r = Float.parseFloat(p[0]);
                float g = Float.parseFloat(p[1]);
                float b = Float.parseFloat(p[2]);
                if (mode == 3) data3D.add(new float[]{r,g,b});
                else if (mode == 1) data1D.add(new float[]{r,g,b});
                else {
                    // No declared section yet – assume 3D by default
                    data3D.add(new float[]{r,g,b});
                }
            }
        }
        br.close();

        if (size3D > 0) {
            int expected = size3D*size3D*size3D;
            if (data3D.size() != expected) {
                throw new IOException("Invalid LUT (" + debugName + "): RGB count mismatch. expected " + expected + " rows, got " + data3D.size());
            }
            float[] table = flattenRgbTriples(data3D);
            clamp01InPlace(table);
            return new CubeLut(size3D, table);
        }

        if (size1D > 0 && data1D.size() == size1D) {
            // Upconvert 1D curves to a 3D cube
            int outSize = Math.max(17, size1D);
            float[] rC = new float[size1D], gC = new float[size1D], bC = new float[size1D];
            for (int i = 0; i < size1D; i++) {
                float[] v = data1D.get(i);
                rC[i] = v[0]; gC[i] = v[1]; bC[i] = v[2];
            }
            float[] table = new float[outSize*outSize*outSize*3];
            int N = outSize;
            int idx = 0;
            for (int r = 0; r < N; r++) {
                float xr = r / (float)(N-1);
                for (int g = 0; g < N; g++) {
                    float xg = g / (float)(N-1);
                    for (int b = 0; b < N; b++) {
                        float xb = b / (float)(N-1);
                        table[idx++] = lut1dSample(rC, xr);
                        table[idx++] = lut1dSample(gC, xg);
                        table[idx++] = lut1dSample(bC, xb);
                    }
                }
            }
            clamp01InPlace(table);
            return new CubeLut(outSize, table);
        }

        throw new IOException("Invalid LUT (" + debugName + "): missing LUT_3D_SIZE and/or data.");
    }

    private static float[] flattenRgbTriples(ArrayList<float[]> list) {
        float[] out = new float[list.size()*3];
        int i = 0;
        for (float[] v : list) {
            out[i++] = v[0];
            out[i++] = v[1];
            out[i++] = v[2];
        }
        return out;
    }

    private static void clamp01InPlace(float[] a) {
        for (int i = 0; i < a.length; i++) {
            float v = a[i];
            if (v < 0f) v = 0f;
            else if (v > 1f) v = 1f;
            a[i] = v;
        }
    }

    private static float lut1dSample(float[] curve, float t) {
        if (curve.length == 1) return curve[0];
        t = Math.max(0f, Math.min(1f, t));
        float p = t * (curve.length - 1);
        int i0 = (int)Math.floor(p);
        int i1 = Math.min(curve.length - 1, i0 + 1);
        float f = p - i0;
        return curve[i0] + (curve[i1] - curve[i0]) * f;
    }

    private static Bitmap applyLutToBitmap(Bitmap src, CubeLut lut) {
        int w = src.getWidth(), h = src.getHeight(); int[] px = new int[w*h]; src.getPixels(px, 0, w, 0, 0, w, h);
        final int N = lut.size; final float s = N - 1f;
        for (int i = 0; i < px.length; i++) {
            int c = px[i]; int a = (c >>> 24) & 0xFF;
            float r = ((c >>> 16) & 0xFF) / 255f, g = ((c >>> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            float rf = r*s, gf = g*s, bf = b*s;
            int r0=(int)Math.floor(rf), g0=(int)Math.floor(gf), b0=(int)Math.floor(bf);
            int r1=clampIdx(r0+1,N), g1=clampIdx(g0+1,N), b1=clampIdx(b0+1,N);
            float fr=rf-r0, fg=gf-g0, fb=bf-b0;
            float[] c000=fetch(lut,r0,g0,b0), c001=fetch(lut,r0,g0,b1), c010=fetch(lut,r0,g1,b0), c011=fetch(lut,r0,g1,b1);
            float[] c100=fetch(lut,r1,g0,b0), c101=fetch(lut,r1,g0,b1), c110=fetch(lut,r1,g1,b0), c111=fetch(lut,r1,g1,b1);
            float[] c00=lerp3(c000,c001,fb), c01=lerp3(c010,c011,fb), c10=lerp3(c100,c101,fb), c11=lerp3(c110,c111,fb);
            float[] c0v=lerp3(c00,c01,fg), c1v=lerp3(c10,c11,fg), out=lerp3(c0v,c1v,fr);
            int rr=clamp8(Math.round(out[0]*255f)), gg=clamp8(Math.round(out[1]*255f)), bb=clamp8(Math.round(out[2]*255f));
            px[i]=(a<<24)|(rr<<16)|(gg<<8)|bb;
        }
        Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); dst.setPixels(px, 0, w, 0, 0, w, h); return dst;
    }

    private static float[] fetch(CubeLut lut, int r, int g, int b) {
        int N = lut.size; r=clampIdx(r,N); g=clampIdx(g,N); b=clampIdx(b,N); int base = (r*N*N + g*N + b) * 3;
        return new float[]{ lut.data[base], lut.data[base+1], lut.data[base+2] };
    }
    private static float[] lerp3(float[] a, float[] b, float t) {
        return new float[]{ a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, a[2]+(b[2]-a[2])*t };
    }

    // ---- helpers ----
    private static boolean startsWithWord(String line, String word) {
        if (!line.startsWith(word)) return false;
        if (line.length() == word.length()) return true;
        char c = line.charAt(word.length());
        return Character.isWhitespace(c);
    }
    private static boolean startsWithWordCI(String s, String word) {
        if (!s.regionMatches(true, 0, word, 0, word.length())) return false;
        if (s.length() == word.length()) return true;
        char c = s.charAt(word.length());
        return Character.isWhitespace(c);
    }
    private static boolean isCommentLine(String s) {
        if (s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (c0 == '#' || c0 == ';') return true;
        return (c0 == '/' && s.length() > 1 && s.charAt(1) == '/');
    }
    private static int dp(int v) { return Math.round(v * Resources.getSystem().getDisplayMetrics().density); }
    private static Drawable makeChipBg(int color, float radiusDp) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(radiusDp); return d; }
    private static Bitmap scaleForPreview(Bitmap src, int targetW) {
        if (src.getWidth() <= targetW) return src.copy(Bitmap.Config.ARGB_8888, false);
        float scale = targetW / (float) src.getWidth(); int w = targetW; int h = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap out = Bitmap.createScaledBitmap(src, w, h, true);
        if (out.getConfig() != Bitmap.Config.ARGB_8888) out = out.copy(Bitmap.Config.ARGB_8888, false);
        return out;
    }
    private static Bitmap makeGradientPreview(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] cols = {0xFF88C9FF, 0xFFFFD3B3, 0xFF9BE37D, 0xFFB39DDB, 0xFFE57373};
        int bar = Math.max(1, h / cols.length);
        for (int i = 0; i < cols.length; i++) { p.setColor(cols[i]); c.drawRect(0, i*bar, w, (i+1)*bar, p); }
        return bmp;
    }
    private static int clamp8(int v){ return v<0?0:(v>255?255:v); }
    private static int clampIdx(int i, int N){ return i<0?0:(i>=N?N-1:i); }

    @Override public void onDestroyView() {
        super.onDestroyView();
        exec.shutdownNow();
        for (Bitmap b : previewCache.values()) { try { if (b != null && !b.isRecycled()) b.recycle(); } catch (Throwable ignore) {} }
        previewCache.clear();
    }
}
