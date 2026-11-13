package tv.oldtv.kit;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示界面：左栏导航 + 卡片网格 + 焦点描边 + 底部小提示 + 本地历史（MENU 删除）。
 * 全部用系统原生 View 手搭，不引 AppCompat / RecyclerView —— 4.x 电视上少一层依赖就少一次崩。
 * 想接自己的数据源，只需要替换 buildItems() 与 fakePlay()。
 */
public class DemoActivity extends Activity {

    private static final int MODE_HOME = 0;
    private static final int MODE_HIST = 1;
    private static final int MODE_FAV = 2;
    private static final int MODE_SET = 3;

    private static final String[] RAIL = {"首页", "历史", "收藏", "设置"};

    private int mode = MODE_HOME;
    private LinearLayout railBar;
    private FrameLayout content;
    private TextView hint;
    private final Handler ui = new Handler();

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // 左栏
        railBar = new LinearLayout(this);
        railBar.setOrientation(LinearLayout.VERTICAL);
        railBar.setBackgroundColor(Ui.RAIL);
        railBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 24), Ui.dp(this, 10), 0);
        for (int i = 0; i < RAIL.length; i++) railBar.addView(railItem(i), railParams());

        // 右侧内容区
        content = new FrameLayout(this);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.leftMargin = Ui.dp(this, 150);
        clp.topMargin = Ui.dp(this, 18);
        clp.rightMargin = Ui.dp(this, 28);
        root.addView(content, clp);

        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                Ui.dp(this, 150), ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(railBar, rlp);

        // 底部小提示：只在需要时冒一下，正常观看不占画面
        hint = new TextView(this);
        hint.setTextColor(Ui.TEXT);
        hint.setTextSize(20);
        hint.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        hint.setBackground(Ui.round(0xCC000000, 6, this));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = Ui.dp(this, 60);
        root.addView(hint, hlp);

        setContentView(root);
        setMode(MODE_HOME);
    }

    // ---------------- 左栏 ----------------

    private LinearLayout.LayoutParams railParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        lp.bottomMargin = Ui.dp(this, 8);
        return lp;
    }

    private View railItem(final int idx) {
        final TextView t = new TextView(this);
        t.setText(RAIL[idx]);
        t.setTextSize(18);
        t.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 14), 0, 0, 0);
        t.setFocusable(true);
        t.setTextColor(Ui.MUTED);
        t.setBackground(Ui.round(Ui.SURFACE, 6, this));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean f) {
                t.setTextColor(f ? Ui.TEXT : Ui.MUTED);
                t.setBackground(f ? Ui.roundStroke(Ui.SURFACE_HI, 6, Ui.ACCENT, 2, DemoActivity.this)
                        : Ui.round(Ui.SURFACE, 6, DemoActivity.this));
                if (f) setMode(idx);
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode(idx);
            }
        });
        return t;
    }

    // ---------------- 内容区 ----------------

    private void setMode(int m) {
        mode = m;
        content.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(RAIL[m]);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(30);
        title.setPadding(0, 0, 0, Ui.dp(this, 14));
        page.addView(title);

        List<String[]> items = buildItems(m);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                page.addView(row);
            }
            row.addView(card(items.get(i)), cardParams());
        }
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 焦点落到第一个卡片，遥控器一进来就能直接按
        if (page.getChildCount() > 1) {
            LinearLayout firstRow = (LinearLayout) page.getChildAt(1);
            if (firstRow.getChildCount() > 0) firstRow.getChildAt(0).requestFocus();
        }
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 240), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 18);
        lp.bottomMargin = Ui.dp(this, 18);
        return lp;
    }

    /** 演示数据；接自己的源时改这里即可 */
    private List<String[]> buildItems(int m) {
        List<String[]> out = new ArrayList<String[]>();
        if (m == MODE_HIST) {
            JSONArray hs = Store.hist(this);
            for (int i = 0; i < hs.length(); i++) {
                JSONObject o = hs.optJSONObject(i);
                if (o == null) continue;
                out.add(new String[]{o.optString("id"), o.optString("title")});
            }
            return out;
        }
        if (m == MODE_SET) return out;   // 设置页留白，示例不展开
        for (int i = 1; i <= 8; i++) {
            out.add(new String[]{"demo-" + i, (m == MODE_FAV ? "收藏 · 示例剧 " : "示例剧 ") + i});
        }
        return out;
    }

    private View card(final String[] item) {
        final FrameLayout box = new FrameLayout(this);
        box.setFocusable(true);
        box.setBackground(Ui.round(Ui.SURFACE, 8, this));
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8));

        // 封面占位：真实项目里换成 Ui.covers(this).load(url, cover)
        ImageView cover = new ImageView(this);
        cover.setBackground(Ui.round(0xFF2A2A33, 6, this));
        box.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 135)));

        TextView name = new TextView(this);
        name.setText(item[1]);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(16);
        name.setMinLines(2);            // 固定两行，卡片等高，避免标题长短导致整排错位
        name.setMaxLines(2);
        name.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), 0);
        name.setTag(item[0]);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        box.addView(name, nlp);

        box.setTag(item[0]);
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean f) {
                v.setBackground(f ? Ui.roundStroke(Ui.SURFACE_HI, 8, Ui.ACCENT, 2, DemoActivity.this)
                        : Ui.round(Ui.SURFACE, 8, DemoActivity.this));
            }
        });
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fakePlay(item[0], item[1]);
            }
        });
        return box;
    }

    /** 模拟一次播放：写进本地历史 + 底部小提示 */
    private void fakePlay(String id, String title) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("episode_index", 0);
            o.put("updated_at", System.currentTimeMillis());
            Store.putHist(this, o);
        } catch (Throwable ignored) {
        }
        flash("正在播放《" + title + "》");
    }

    private void flash(String text) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(new Runnable() {
            public void run() {
                hint.setVisibility(View.GONE);
            }
        }, 1500);
    }

    // ---------------- 按键 ----------------

    public boolean onKeyDown(int code, KeyEvent e) {
        // 历史页：MENU 删掉焦点所在的这条记录
        if (code == KeyEvent.KEYCODE_MENU && mode == MODE_HIST) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) {
                Store.removeHist(DemoActivity.this, (String) f.getTag());
                flash("已删除这条记录");
                setMode(MODE_HIST);
                return true;
            }
        }
        return super.onKeyDown(code, e);
    }

    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
