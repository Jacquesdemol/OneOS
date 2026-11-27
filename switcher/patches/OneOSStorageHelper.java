package com.android.server.pm;

import android.content.pm.PackageStats;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Slog;
import com.android.server.pm.pkg.AndroidPackage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bridges guest storage accounting and app cache/data clearing to oneos_storaged
 * running inside the guest mount namespace over /data/system/oneos_storage.sock.
 *
 * Host installd resolves /data_mirror/data_{ce,de}/null/0 on One UI's userdata and
 * has incompatible One UI 8 vs One UI 9.5 AIDL signatures for getAppSize/getUserSize.
 */
public final class OneOSStorageHelper {
    private static final String TAG = "OneOSStorage";
    private static final String SOCK_PATH = "/data/system/oneos_storage.sock";

    private OneOSStorageHelper() {}

    public static boolean getAppSize(
            String volumeUuid,
            String[] packageNames,
            int userId,
            int flags,
            int appId,
            int pccAppId,
            long[] ceDataInodes,
            String[] codePaths,
            PackageStats stats) {
        if (volumeUuid != null) {
            return false;
        }
        long[] vals = getAppSizeAndFileCount(
                volumeUuid, packageNames, userId, flags, appId, pccAppId, ceDataInodes, codePaths);
        if (vals != null && stats != null && vals.length >= 6) {
            stats.codeSize += vals[0];
            stats.dataSize += vals[1];
            stats.cacheSize += vals[2];
            stats.externalCodeSize += vals[3];
            stats.externalDataSize += vals[4];
            stats.externalCacheSize += vals[5];
        }
        return true;
    }

    public static long[] getAppSizeAndFileCount(
            String volumeUuid,
            String[] packageNames,
            int userId,
            int flags,
            int appId,
            int pccAppId,
            long[] ceDataInodes,
            String[] codePaths) {
        if (volumeUuid != null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("APP_SIZE\t").append(userId).append('\t');
        appendJoined(sb, packageNames, ',');
        sb.append('\t');
        appendJoined(sb, codePaths, '|');
        long[] parsed = queryLongs(sb.toString(), 12);
        return parsed != null ? parsed : new long[12];
    }

    public static boolean getUserSize(
            String volumeUuid,
            int userId,
            int flags,
            int[] appIds,
            int[] pccAppIds,
            PackageStats stats) {
        if (volumeUuid != null) {
            return false;
        }
        long[] vals = queryLongs("USER_SIZE\t" + userId, 6);
        if (vals != null && stats != null && vals.length >= 6) {
            stats.codeSize += vals[0];
            stats.dataSize += vals[1];
            stats.cacheSize += vals[2];
            stats.externalCodeSize += vals[3];
            stats.externalDataSize += vals[4];
            stats.externalCacheSize += vals[5];
        }
        return true;
    }

    public static long[] getExternalSize(String volumeUuid, int userId, int flags, int[] appIds) {
        if (volumeUuid != null) {
            return null;
        }
        long[] vals = queryLongs("EXT_SIZE\t" + userId, 6);
        return vals != null ? vals : new long[6];
    }

    public static void clearAppCache(AndroidPackage pkg, int userId) {
        if (pkg == null) {
            return;
        }
        String name = pkg.getPackageName();
        if (name == null || name.isEmpty()) {
            return;
        }
        int u = userId >= 0 ? userId : 0;
        transact("CLEAR_CACHE\t" + u + '\t' + name);
    }

    public static void clearAppData(AndroidPackage pkg, int userId) {
        if (pkg == null) {
            return;
        }
        String name = pkg.getPackageName();
        if (name == null || name.isEmpty()) {
            return;
        }
        int u = userId >= 0 ? userId : 0;
        transact("CLEAR_DATA\t" + u + '\t' + name);
    }

    private static void appendJoined(StringBuilder sb, String[] items, char sep) {
        if (items == null) {
            return;
        }
        boolean first = true;
        for (int i = 0; i < items.length; i++) {
            String s = items[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(sep);
            }
            sb.append(s);
            first = false;
        }
    }

    private static long[] queryLongs(String req, int count) {
        String resp = transact(req);
        if (resp == null || !resp.startsWith("OK ")) {
            return null;
        }
        String[] parts = resp.substring(3).split(" ");
        if (parts.length < count) {
            return null;
        }
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0L;
            }
        }
        return out;
    }

    private static String transact(String req) {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCK_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            sock.setSoTimeout(5000);
            OutputStream out = sock.getOutputStream();
            out.write((req + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (Throwable t) {
            Slog.w(TAG, "oneos_storaged request failed: " + req, t);
            return null;
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
