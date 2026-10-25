package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * App folders on the desk. Only apps that are not in the dock can live in a folder; the adapter
 * draws one glass tile per folder and the folder's own panel lists its apps.
 *
 * <p>Stored as a small JSON array in its own preferences file. Loss is not worth crashing over: a
 * corrupt value reads as "no folders" and the next edit rewrites it.
 */
final class FolderStore {

    static final class Folder {
        final String id;
        final String name;
        final List<String> pkgs;

        Folder(String id, String name, List<String> pkgs) {
            this.id = id;
            this.name = name;
            this.pkgs = pkgs;
        }
    }

    private static final String PREFS = "folders";
    private static final String KEY_LIST = "list";

    private final Context ctx;
    private final SharedPreferences prefs;

    FolderStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Folder> all() {
        List<Folder> out = new ArrayList<>();
        String raw = prefs.getString(KEY_LIST, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                List<String> pkgs = new ArrayList<>();
                JSONArray list = o.optJSONArray("pkgs");
                if (list != null) {
                    for (int j = 0; j < list.length(); j++) {
                        String p = list.optString(j, "").trim();
                        if (!p.isEmpty() && !pkgs.contains(p)) pkgs.add(p);
                    }
                }
                if (pkgs.isEmpty()) continue;   // an empty folder is not a folder
                out.add(new Folder(id, o.optString("name", ""), pkgs));
            }
        } catch (Exception e) {
            Errors.record("folders", "read " + e.getClass().getSimpleName());
        }
        return out;
    }

    /** One folder by id, or null once it has been dissolved. */
    Folder byId(String id) {
        for (Folder f : all()) {
            if (f.id.equals(id)) return f;
        }
        return null;
    }

    /** The folder holding this package, or null. */
    Folder folderOf(String pkg) {
        if (pkg == null) return null;
        for (Folder f : all()) {
            if (f.pkgs.contains(pkg)) return f;
        }
        return null;
    }

    /** A new folder holding [pkg], or the existing folder when the app already lives in one. */
    Folder create(String pkg) {
        Folder existing = folderOf(pkg);
        if (existing != null) return existing;
        List<Folder> list = all();
        String id = "f" + System.currentTimeMillis();
        while (byId(id) != null) id += "x";   // two folders in the same millisecond must differ
        String name = ctx.getString(R.string.folder_name);
        // "文件夹" then "文件夹 2", "文件夹 3"…: the remote has no keyboard, so names are automatic.
        if (usedName(list, name)) {
            int n = 2;
            while (usedName(list, name + " " + n)) n++;
            name = name + " " + n;
        }
        List<String> pkgs = new ArrayList<>();
        pkgs.add(pkg);
        list.add(new Folder(id, name, pkgs));
        save(list);
        return list.get(list.size() - 1);
    }

    /** Put [pkg] in [id], taking it out of any other folder first. */
    void add(String id, String pkg) {
        List<Folder> list = all();
        for (Folder f : list) {
            if (!f.id.equals(id)) f.pkgs.remove(pkg);
        }
        for (Folder f : list) {
            if (f.id.equals(id) && !f.pkgs.contains(pkg)) f.pkgs.add(pkg);
        }
        save(list);
    }

    /** Drop [pkg] from whatever folder holds it; a folder left empty disappears. */
    void remove(String pkg) {
        List<Folder> list = all();
        boolean changed = false;
        for (Iterator<Folder> it = list.iterator(); it.hasNext(); ) {
            Folder f = it.next();
            if (f.pkgs.remove(pkg)) changed = true;
            if (f.pkgs.isEmpty()) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save(list);
    }

    void dissolve(String id) {
        List<Folder> list = all();
        for (Iterator<Folder> it = list.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(id)) it.remove();
        }
        save(list);
    }

    /** Drop uninstalled apps and anything now pinned to the dock. */
    void prune(Set<String> installed, Set<String> docked) {
        List<Folder> list = all();
        boolean changed = false;
        for (Iterator<Folder> it = list.iterator(); it.hasNext(); ) {
            Folder f = it.next();
            int before = f.pkgs.size();
            for (Iterator<String> p = f.pkgs.iterator(); p.hasNext(); ) {
                String pkg = p.next();
                if (!installed.contains(pkg) || docked.contains(pkg)) p.remove();
            }
            if (f.pkgs.size() != before) changed = true;
            if (f.pkgs.isEmpty()) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save(list);
    }

    private boolean usedName(List<Folder> list, String name) {
        for (Folder f : list) {
            if (name.equals(f.name)) return true;
        }
        return false;
    }

    private void save(List<Folder> list) {
        JSONArray arr = new JSONArray();
        for (Folder f : list) {
            if (f.pkgs.isEmpty()) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("id", f.id);
                o.put("name", f.name);
                JSONArray pkgs = new JSONArray();
                for (String p : f.pkgs) pkgs.put(p);
                o.put("pkgs", pkgs);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }
}
