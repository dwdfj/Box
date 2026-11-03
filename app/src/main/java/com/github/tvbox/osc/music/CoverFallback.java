package com.github.tvbox.osc.music;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.widget.ImageView;

/**
 * 小贾影视仓 v27.16: 无封面兜底。
 *
 * 背景: 音源多了以后, 部分歌曲(尤其各平台交叉匹配到的冷门曲)拿不到封面, 界面就是一片黑块。
 * 网易接口的 picUrl 我们已经在 MusicApi.fillCovers 里补了, 但网络失败/图 404 时仍会空。
 *
 * 做法: 用歌名首字 + 由歌名哈希出的渐变色画一张占位图, 保证任何情况下都有东西显示,
 * 而不是黑块。颜色只从已定的两个强调色(雾蓝 #7AA8FF / 雾紫 #B07AFF)派生, 不乱加色。
 */
public final class CoverFallback {
    private static final int C_A = 0xFF7AA8FF;
    private static final int C_B = 0xFFB07AFF;
    private static final int C_C = 0xFF3E5C8A;

    private CoverFallback() {
    }

    /** 取歌名的第一个可见字符(跳过括号、空格)。 */
    public static String initial(String name) {
        if (name == null || name.isEmpty()) return "♪";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(c).toUpperCase();
        }
        return "♪";
    }

    /** 由歌名稳定地派生一对渐变色(同一首歌永远同一个颜色)。 */
    private static int[] colors(String seed) {
        int h = seed == null ? 0 : seed.hashCode();
        int k = Math.abs(h) % 3;
        switch (k) {
            case 0: return new int[]{C_A, C_C};
            case 1: return new int[]{C_B, C_C};
            default: return new int[]{C_A, C_B};
        }
    }

    /** 画一张 size×size 的渐变占位图(带首字)。 */
    public static Bitmap render(String name, int size) {
        if (size <= 0) size = 256;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int[] cs = colors(name);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, size, size, cs[0], cs[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);

        // 右上角一团柔光, 避免纯渐变显得死板
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(Color.argb(46, 255, 255, 255));
        c.drawCircle(size * 0.78f, size * 0.22f, size * 0.34f, glow);

        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.argb(235, 255, 255, 255));
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setTextSize(size * 0.42f);
        Paint.FontMetrics fm = t.getFontMetrics();
        float cy = size / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(initial(name), size / 2f, cy, t);
        return bmp;
    }

    /** 直接铺到 ImageView 上(圆角由 ImageView 自己裁)。 */
    public static void apply(ImageView iv, String name) {
        if (iv == null) return;
        int w = iv.getWidth() > 0 ? iv.getWidth() : 256;
        iv.setImageBitmap(render(name, w));
    }

    /**
     * 统一的封面加载: 有 url 走 Glide(失败/加载中都铺首字占位图), 没 url 直接铺占位图。
     * 各处列表/详情页一律用这个, 避免再出现黑块。
     */
    public static void load(ImageView iv, String url, String name) {
        if (iv == null) return;
        android.graphics.drawable.Drawable fb = new android.graphics.drawable.BitmapDrawable(
                iv.getResources(), render(name, 384));
        if (url != null && !url.isEmpty()) {
            com.bumptech.glide.Glide.with(iv.getContext()).load(url)
                    .placeholder(fb).error(fb).into(iv);
        } else {
            iv.setImageDrawable(fb);
        }
    }
}
