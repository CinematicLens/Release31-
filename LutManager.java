// File: LutManager.java
package com.squeezer.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * One-stop LUT manager:
 *  - Lists .cube files under assets/luts (flat + simple recursive for grouping)
 *  - Loads & parses .cube into a 3D LUT GL texture (via ShaderUtils.createLUTTexture)
 *  - Caches (textureId, lutSize)
 *  - Provides ID helpers: "asset:<name>" / "file:<abs path>"
 */
public final class LutManager {
    private static final String TAG = "LUT";
    private static final String FOLDER = "luts";

    private LutManager() {}

    // ---------- ID helpers ----------
    /** "asset:TealOrange.cube" */
    public static String toAssetId(String name) {
        if (name == null) return null;
        return name.startsWith("asset:") ? name : "asset:" + name;
    }
    /** "file:/abs/path/MyLook.cube" */
    public static String toFileId(File f) { return "file:" + f.getAbsolutePath(); }
    /** If lutId is "file:/..", return absolute path; else null. */
    public static String resolvePathIfFile(String lutId) {
        if (lutId != null && lutId.startsWith("file:")) return lutId.substring("file:".length());
        return null;
    }
    /** Display-friendly name: drop folders/ext/ids, replace underscores. */
    public static String prettyTitle(String nameOrPath) {
        if (nameOrPath == null) return "None";
        String s = nameOrPath;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        int colon = s.indexOf(':'); // "asset:Foo.cube"
        if (colon >= 0) s = s.substring(colon + 1);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.replace('_', ' ');
    }
    /** Directory for imported/user LUTs. */
    public static File getUserLutsDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "user_luts");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
    /** Open a LUT by app-level ID: "asset:<name.cube>" or "file:/abs/path.cube". */
    public static InputStream openLutStream(Context ctx, String lutId) throws IOException {
        if (lutId == null) throw new IOException("lutId is null");
        if (lutId.startsWith("asset:")) {
            String name = lutId.substring("asset:".length());
            return ctx.getAssets().open(FOLDER + "/" + name);
        }
        if (lutId.startsWith("file:")) {
            String path = lutId.substring("file:".length());
            return new FileInputStream(path);
        }
        // Back-compat: treat raw name as asset
        return ctx.getAssets().open(FOLDER + "/" + lutId);
    }

    // ---------- Listing ----------
    /** Top-level names like "TealOrange.cube", A–Z. */
    public static List<String> listLutNames(Context ctx) {
        try {
            String[] all = ctx.getAssets().list(FOLDER);
            if (all == null) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (String f : all) if (f.toLowerCase().endsWith(".cube")) out.add(f);
            out.sort(String::compareToIgnoreCase);
            return out;
        } catch (IOException e) {
            Log.w(TAG, "listLutNames: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Recursively list .cube under assets/luts, returning relative paths like:
     *  "Free/TealOrange.cube", "Pro/Blockbuster/Cobalt.cube", or "Neutral.cube".
     * Handy for UI grouping. Depth-limited.
     */
    public static List<String> listAssetLuts(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            AssetManager am = ctx.getAssets();
            ArrayDeque<String> q = new ArrayDeque<>();
            q.add(FOLDER);
            int maxDepth = 4;
            while (!q.isEmpty()) {
                String dir = q.removeFirst();
                String[] children = am.list(dir);
                if (children == null) continue;
                for (String ch : children) {
                    String full = dir + "/" + ch;
                    if (ch.toLowerCase().endsWith(".cube")) {
                        out.add(full.substring(FOLDER.length() + 1)); // relative to luts/
                    } else if (!ch.contains(".") && depthOf(full) <= maxDepth) {
                        String[] sub = am.list(full);
                        if (sub != null && sub.length > 0) q.add(full);
                    }
                }
            }
            out.sort(String::compareToIgnoreCase);
        } catch (IOException e) {
            Log.w(TAG, "listAssetLuts: " + e.getMessage());
        }
        return out;
    }

    private static int depthOf(String path) {
        int d = 0; for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') d++; return d;
    }

    // ---------- GL texture cache & loaders ----------
    private static final int MAX_CACHE = 12;
    private static final LinkedHashMap<String, Pair<Integer, Integer>> CACHE =
            new LinkedHashMap<String, Pair<Integer, Integer>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Entry<String, Pair<Integer, Integer>> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    /** Clears cache entries (does NOT delete GL textures). */
    public static void clearCache() { CACHE.clear(); }

    /** Load (or cached) for asset file name (e.g., "TealOrange.cube"). */
    public static Pair<Integer, Integer> getOrLoad(Context ctx, String lutName) throws IOException {
        if (lutName == null || lutName.trim().isEmpty()) throw new IOException("LUT name is empty");
        Pair<Integer, Integer> hit = CACHE.get("asset:" + lutName);
        if (hit != null) return hit;
        try (InputStream is = ctx.getAssets().open(FOLDER + "/" + lutName)) {
            Pair<Integer, Integer> tex = loadCubeLUT(is);
            CACHE.put("asset:" + lutName, tex);
            return tex;
        }
    }
    /** Non-throwing variant. */
    public static Pair<Integer, Integer> getOrLoadSafe(Context ctx, String lutName) {
        try { return getOrLoad(ctx, lutName); }
        catch (Throwable t) { Log.w(TAG, "getOrLoadSafe failed for " + lutName, t); return new Pair<>(0, 33); }
    }
    /** Load (or cached) from a SAF Uri. */
    public static Pair<Integer, Integer> getOrLoadExternal(Context ctx, Uri uri) throws IOException {
        if (uri == null) throw new IOException("LUT Uri is null");
        final String key = "ext:" + uri.toString();
        Pair<Integer, Integer> hit = CACHE.get(key);
        if (hit != null) return hit;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Unable to open LUT Uri");
            Pair<Integer, Integer> tex = loadCubeLUT(is);
            CACHE.put(key, tex);
            return tex;
        }
    }
    public static Pair<Integer, Integer> getOrLoadExternalSafe(Context ctx, Uri uri) {
        try { return getOrLoadExternal(ctx, uri); }
        catch (Throwable t) { Log.w(TAG, "getOrLoadExternalSafe failed for " + uri, t); return new Pair<>(0, 33); }
    }

    // ---------- .cube parsing + 3D LUT texture ----------
    private static Pair<Integer, Integer> loadCubeLUT(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        List<Float> rgbList = new ArrayList<>();
        int lutSize = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (startsWithWord(line, "TITLE") ||
                    startsWithWord(line, "DOMAIN_MIN") ||
                    startsWithWord(line, "DOMAIN_MAX")) {
                continue;
            }
            if (startsWithWord(line, "LUT_3D_SIZE")) {
                int space = line.indexOf(' ');
                if (space > 0) lutSize = Integer.parseInt(line.substring(space + 1).trim());
                continue;
            }

            char c0 = line.charAt(0);
            if (Character.isDigit(c0) || c0 == '-' || c0 == '+') {
                String[] p = line.split("\\s+");
                if (p.length >= 3) {
                    try {
                        rgbList.add(Float.parseFloat(p[0]));
                        rgbList.add(Float.parseFloat(p[1]));
                        rgbList.add(Float.parseFloat(p[2]));
                    } catch (NumberFormatException ignore) {}
                }
            }
        }
        closeQuietly(reader);

        if (lutSize <= 0) throw new IOException("Missing or invalid LUT_3D_SIZE");
        int expected = lutSize * lutSize * lutSize * 3;
        if (rgbList.size() != expected) throw new IOException("Invalid RGB count. expected=" + expected + " got=" + rgbList.size());

        float[] data = new float[rgbList.size()];
        for (int i = 0; i < data.length; i++) data[i] = rgbList.get(i);

        int textureId = ShaderUtils.createLUTTexture(data, lutSize);
        Log.d(TAG, "Created LUT texture id=" + textureId + " size=" + lutSize);
        return new Pair<>(textureId, lutSize);
    }

    private static boolean startsWithWord(String line, String word) {
        if (!line.startsWith(word)) return false;
        if (line.length() == word.length()) return true;
        char c = line.charAt(word.length());
        return Character.isWhitespace(c);
    }
    private static void closeQuietly(Closeable c) { try { if (c != null) c.close(); } catch (Throwable ignore) {} }
}
