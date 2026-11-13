package tv.oldtv.kit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 低版本电视上的视觉与图片工具集（API 14 起可用，实机 Android 4.3 / API 18）。
 *
 * 为什么单独拆出来：
 * 1) 4.x 没有 ImageView.setImageTintList，只能用 PorterDuff 上色，asset 里放单色 png；
 * 2) 4.3 的 JSSE 默认不发 TLS1.2 的 ClientHello，很多图床/接口的 HTTPS 会直接握手失败，
 *    这里带一次「放宽 TLS 版本 + 宽松信任」的重试；
 * 3) 封面按像素缓存容易把 512MB 内存的电视盒子拖死，这里按「可用内存的 1/4」做 LruCache。
 */
public class Ui {

    // 近黑底 + 白字 + 灰字副信息，强调色是暖橙
    public static final int BG = 0xFF0E0E12;
    public static final int RAIL = 0xFF08080A;
    public static final int SURFACE = 0xFF17171C;
    public static final int SURFACE_HI = 0xFF23232B;
    public static final int ACCENT = 0xFFFF7844;
    public static final int TEXT = 0xFFE9E9EF;
    public static final int MUTED = 0xFF8A8A93;
    public static final int BADGE = 0xCC000000;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int fill, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(int fill, int radiusDp, int strokeColor, float strokeDp, Context c) {
        GradientDrawable d = round(fill, radiusDp, c);
        d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return d;
    }

    /**
     * 焦点态：只描边、不缩放。
     * 缩放会把卡片撑出格子、压到邻居的描边上，上下移动光标时整片画面会「跳」，低端电视尤其明显。
     */
    public static GradientDrawable focusBox(Context c, boolean focused) {
        return focused ? roundStroke(SURFACE_HI, 8, ACCENT, 2, c) : round(SURFACE, 8, c);
    }

    public static GradientDrawable posterBox(Context c) {
        return round(SURFACE, 8, c);
    }

    private static CoverLoader loader;

    public static synchronized CoverLoader covers(Context c) {
        if (loader == null) loader = new CoverLoader(c.getApplicationContext());
        return loader;
    }

    public static String human(long n) {
        if (n >= 100000000L) return String.format("%.1f亿", n / 100000000.0);
        if (n >= 10000L) return String.format("%.1f万", n / 10000.0);
        return String.valueOf(n);
    }

    /** 从 assets/icons 读单色 png，再按颜色上色（API18 没有 tint，只能 colorFilter） */
    public static Drawable icon(Context c, String name, int color) {
        try {
            Bitmap bm = BitmapFactory.decodeStream(c.getAssets().open("icons/" + name + ".png"));
            if (bm == null) return null;
            BitmapDrawable bd = new BitmapDrawable(c.getResources(), bm);
            bd.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            return bd;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 封面加载：线程池 + LruCache，失败再来一次放宽 TLS 的重试 */
    public static class CoverLoader {
        private final LruCache<String, Bitmap> cache;
        private final ExecutorService pool = Executors.newFixedThreadPool(3);
        private javax.net.ssl.SSLSocketFactory sslFactory;

        @SuppressWarnings("unchecked")
        CoverLoader(Context ctx) {
            int max = (int) (Runtime.getRuntime().maxMemory() / 1024 / 4);
            cache = new LruCache<String, Bitmap>(max) {
                protected int sizeOf(String k, Bitmap v) {
                    return v.getByteCount() / 1024;
                }
            };
        }

        public Bitmap cached(String url) {
            return cache.get(url);
        }

        public void clear() {
            cache.evictAll();
        }

        public void load(final String url, final ImageView iv) {
            if (url == null || url.length() == 0) {
                iv.setImageDrawable(null);
                iv.setTag(null);
                return;
            }
            Bitmap b = cache.get(url);
            if (b != null) {
                iv.setTag(url);
                iv.setImageBitmap(b);
                return;
            }
            iv.setImageDrawable(null);
            iv.setTag(url);
            pool.execute(new Runnable() {
                public void run() {
                    final Bitmap bm = fetch(url);
                    if (bm == null) return;
                    cache.put(url, bm);
                    iv.post(new Runnable() {
                        public void run() {
                            if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
                        }
                    });
                }
            });
        }

        private Bitmap fetch(String url) {
            try {
                Bitmap bm = fetchOnce(url, false);
                if (bm != null) return bm;
                return fetchOnce(url, true);
            } catch (Throwable e) {
                return null;
            }
        }

        private Bitmap fetchOnce(String url, boolean relaxed) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Connection", "close");
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.3)");
                if (c instanceof javax.net.ssl.HttpsURLConnection && relaxed) {
                    ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(relaxedSsl());
                }
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) return null;
                InputStream in = c.getInputStream();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap bm = BitmapFactory.decodeStream(in, null, o);
                in.close();
                return bm;
            } catch (Throwable e) {
                android.util.Log.w("TVKIT", "封面失败(" + (relaxed ? "relaxed" : "default") + ") " + e);
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /** 放宽 TLS：4.3 默认只到 TLS1.0，很多 CDN 会直接拒握手 */
        private synchronized javax.net.ssl.SSLSocketFactory relaxedSsl() throws Exception {
            if (sslFactory != null) return sslFactory;
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            sslFactory = ctx.getSocketFactory();
            return sslFactory;
        }
    }
}
