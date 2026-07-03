/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2026 - Emanuele Faranda
 */

package com.adbye.filter;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

public class ZstdDecoder {
    private static final String TAG = "ZstdDecoder";
    private static volatile boolean sNativeAvailable = false;

    static {
        try {
            System.loadLibrary("zstd_dec");
            sNativeAvailable = true;
            Log.d(TAG, "Loaded native zstd library");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native zstd library not available, using fallback: " + e.getMessage());
            sNativeAvailable = false;
        }
    }

    /**
     * Check if native zstd library is available.
     */
    public static boolean isNativeAvailable() {
        return sNativeAvailable;
    }

    /**
     * Decompress a zstd-compressed byte array.
     * @param src the compressed data
     * @return the decompressed data
     * @throws IOException on decompression error
     */
    public static byte[] decompress(byte[] src) throws IOException {
        if (!sNativeAvailable) {
            throw new IOException("ZSTD native library not available - cannot decompress zstd content");
        }
        return nativeDecompress(src);
    }

    /**
     * Native implementation - called when library is loaded.
     */
    private static native byte[] nativeDecompress(byte[] src) throws IOException;

    /**
     * Fallback decompressor that throws - used when native lib unavailable.
     * This method is patched by the native library when loaded.
     */
    static byte[] decompress(byte[] src, boolean unused) throws IOException {
        throw new IOException("ZSTD native library not available");
    }
}

