package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pictures on a plugged-in USB stick. MediaStore indexes the TV's own storage but often misses a
 * stick that was pushed in after boot, which is exactly how wallpapers get onto these sets. This is
 * a bounded scan — three levels deep, a second and a half at most — so the remote never waits on a
 * full-disk crawl.
 */
final class LocalImages {

    private static final long DEADLINE_MS = 1500;

    private LocalImages() {
    }

    static List<File> recent(int max) {
        List<File> out = new ArrayList<>();
        ArrayDeque<File> queue = new ArrayDeque<>();
        for (File root : roots()) queue.add(root);
        final long deadline = System.currentTimeMillis() + DEADLINE_MS;
        int visited = 0;
        while (!queue.isEmpty() && out.size() < max && System.currentTimeMillis() < deadline) {
            File dir = queue.poll();
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (++visited > 600 || out.size() >= max) break;
                String name = f.getName();
                if (name.startsWith(".") || name.equals("Android")) continue;
                if (f.isDirectory()) queue.add(f);
                else if (isImage(name) || isVideo(name)) out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    /** Removable volumes only: the internal one is MediaStore's job and would be listed twice. */
    private static List<File> roots() {
        List<File> roots = new ArrayList<>();
        File storage = new File("/storage");
        File[] children = storage.listFiles();
        if (children != null) {
            for (File f : children) {
                String name = f.getName();
                if (name.equals("emulated") || name.equals("self")) continue;
                if (f.isDirectory()) roots.add(f);
            }
        }
        // Some firmware mounts media outside /storage and leaves no child there.
        for (String path : new String[]{"/mnt/usb", "/mnt/media_rw"}) {
            File f = new File(path);
            if (f.isDirectory()) roots.add(f);
        }
        return roots;
    }

    static boolean isVideo(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".avi")
                || lower.endsWith(".3gp") || lower.endsWith(".ts");
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }
}
