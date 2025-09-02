package com.squeezer.app;

import android.util.Log;
import android.util.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class LUTLoader {
    private static Pair<Integer, Integer> lastLUTSize = new Pair<>(33, 33);  // Default

    public static Pair<Integer, Integer> loadCubeLUT(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        List<Float> rgbList = new ArrayList<>();
        int lutSize = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("LUT_3D_SIZE")) {
                int parsedSize = Integer.parseInt(line.split(" ")[1].trim());
                lutSize = parsedSize;
                lastLUTSize = new Pair<>(parsedSize, parsedSize);
                Log.d("LUT", "✔ Parsed LUT_3D_SIZE = " + parsedSize);
            } else if (!line.startsWith("#") && !line.isEmpty() && Character.isDigit(line.charAt(0))) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    rgbList.add(Float.parseFloat(parts[0]));
                    rgbList.add(Float.parseFloat(parts[1]));
                    rgbList.add(Float.parseFloat(parts[2]));
                }
            }
        }
        reader.close();

        int expectedSize = lutSize * lutSize * lutSize * 3;
        Log.d("LUT", "✔ Parsed RGB float values = " + rgbList.size());
        Log.d("LUT", "✔ Expected RGB float count = " + expectedSize);

        if (lutSize <= 0 || rgbList.size() != expectedSize) {
            throw new IOException("Invalid LUT or size: " + lutSize + ", parsed RGB: " + rgbList.size());
        }

        float[] lutData = new float[rgbList.size()];
        for (int i = 0; i < rgbList.size(); i++) {
            lutData[i] = rgbList.get(i);
        }

        int textureId = ShaderUtils.createLUTTexture(lutData, lutSize);
        return new Pair<>(textureId, lutSize);
    }

    public static Pair<Integer, Integer> getLastLoadedLUTSize() {
        return lastLUTSize;
    }
}


