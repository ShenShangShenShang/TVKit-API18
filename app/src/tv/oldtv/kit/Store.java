package tv.oldtv.kit;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 本地观看历史 / 收藏 / 搜索历史，全部塞在 SharedPreferences 里存 JSON。
 *
 * 为什么不放数据库：老电视上 SQLite 打开慢、升级容易踩到 WAL 兼容问题，
 * 而这几份数据量很小（历史 120 条封顶），一段 JSON 字符串读写就够，
 * 退出前台前 commit 一次即可，冷启动读一次就能直接拼首页。
 */
public class Store {
    private static final String SP = "tvkit_store";
    private static final String K_HIS = "his";
    private static final String K_FAV = "fav";
    private static final String K_SEARCH = "search";
    private static final int CAP = 120;
    private static final int SEARCH_CAP = 12;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static JSONArray arr(Context c, String key) {
        try {
            return new JSONArray(sp(c).getString(key, "[]"));
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, String key, JSONArray a) {
        sp(c).edit().putString(key, a.toString()).commit();
    }

    public static JSONArray hist(Context c) {
        return arr(c, K_HIS);
    }

    public static JSONArray favs(Context c) {
        return arr(c, K_FAV);
    }

    public static JSONObject findHist(Context c, String id) {
        if (id == null) return null;
        JSONArray a = hist(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /** 记一条观看记录：同一 id 只保留一条，并把更新时间顶到最前 */
    public static void putHist(Context c, JSONObject item) {
        JSONArray a = hist(c);
        JSONArray out = new JSONArray();
        out.put(item);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (item.optString("id").equals(o.optString("id"))) continue;
            if (out.length() >= CAP) break;
            out.put(o);
        }
        save(c, K_HIS, out);
    }

    /** API18 的 org.json 没有 JSONArray.remove(int)，自己拼一个新的 */
    public static void removeHist(Context c, String id) {
        JSONArray a = hist(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) out.put(o);
        }
        save(c, K_HIS, out);
    }

    public static void clearHist(Context c) {
        save(c, K_HIS, new JSONArray());
    }

    public static JSONArray searchHist(Context c) {
        return arr(c, K_SEARCH);
    }

    public static void putSearch(Context c, String word) {
        if (word == null) return;
        word = word.trim();
        if (word.length() == 0) return;
        JSONArray a = searchHist(c);
        JSONArray out = new JSONArray();
        out.put(word);
        for (int i = 0; i < a.length(); i++) {
            String w = a.optString(i);
            if (w == null || w.length() == 0 || w.equals(word)) continue;
            if (out.length() >= SEARCH_CAP) break;
            out.put(w);
        }
        save(c, K_SEARCH, out);
    }

    public static void clearSearch(Context c) {
        save(c, K_SEARCH, new JSONArray());
    }
}
