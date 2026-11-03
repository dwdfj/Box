package com.github.tvbox.osc.player;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.base.App;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;

public class MyVideoView extends VideoView implements DrawHandler.Callback {
    private DanmakuView danmuView;

    public MyVideoView(@NonNull Context context) {
        super(context, null);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AbstractPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    /**
     * 小贾影视仓 v27.22: 调整画面在「播放器容器」里的对齐方式。
     * 播放器容器(竖屏播放页)现在比 16:9 画面高 —— 底下留出的黑条专门给控制栏使用。
     * doikki 默认把 renderView 居中(Gravity.CENTER), 画面会浮在容器中间、上下各一条黑边,
     * 看起来像"上下分离"。改成 TOP 后画面顶到容器最上面, 黑条集中到画面下方,
     * 控制栏正好落在黑条里, 不再压住画面。
     */
    public void setRenderGravity(int gravity) {
        pendingGravity = gravity;
        applyRenderGravity();
    }

    private int pendingGravity = -1;

    private void applyRenderGravity() {
        try {
            if (pendingGravity < 0) return;
            if (mRenderView == null) return;
            android.view.View v = mRenderView.getView();
            if (v == null) return;
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                ((android.widget.FrameLayout.LayoutParams) lp).gravity = pendingGravity;
                v.setLayoutParams(lp);
                v.requestLayout();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 渲染视图是在 addDisplay() 里才创建的 —— 播放页可能在之前就调过 setRenderGravity
     * (那时 mRenderView 还是 null), 所以这里重建后要再落一次, 否则首播画面还是居中。
     */
    @Override
    protected void addDisplay() {
        super.addDisplay();
        applyRenderGravity();
        applyPortraitRegion();
    }

    /**
     * 小贾影视仓 v27.25: 竖屏「画面区」高度(px), <=0 表示不限制(横屏全屏)。
     *
     * <p>要解决的是用户截图里的「播放区挺大, 画面只占一半」:
     * 播放容器 = 画面区 + 下方控制栏(控制栏不能压画面, 见 v27.22), 但
     * ① 卡片类片源是 2.4:1(21:9 电影画幅), 塞进 16:9 的画面区后上下各留一条黑边;
     * ② 渲染视图按 FIT 居中, 黑边就留在画面区里。
     * 所以这里把渲染视图的**高度锁成画面区高度**, 宽度按片源比例放大(超出的左右部分被
     * 父容器裁掉), 画面就铺满这块区域, 不再有黑边。
     *
     * <p>为什么是改 LayoutParams 而不是 setScreenScaleType(CENTER_CROP):
     * 控制栏的「缩放」按钮是用户自己的设置(默认/16:9/4:3/填充/原始/裁剪), 直接改 scaleType
     * 会把用户的选择覆盖掉; 而只锁布局尺寸时, 用户点缩放仍然能在这一块里改比例。
     */
    public void setPortraitRegion(int heightPx) {
        mPortraitRegion = heightPx;
        applyPortraitRegion();
    }

    private int mPortraitRegion = 0;

    private void applyPortraitRegion() {
        try {
            if (mRenderView == null) return;
            android.view.View v = mRenderView.getView();
            if (v == null) return;
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (!(lp instanceof android.widget.FrameLayout.LayoutParams)) return;
            android.widget.FrameLayout.LayoutParams flp = (android.widget.FrameLayout.LayoutParams) lp;
            if (mPortraitRegion <= 0) {
                flp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                flp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                flp.gravity = android.view.Gravity.CENTER;
                v.setLayoutParams(flp);
                v.requestLayout();
                return;
            }
            int vw = mVideoSize[0];
            int vh = mVideoSize[1];
            int parentW = getWidth();
            if (parentW <= 0 && getParent() instanceof android.view.View) {
                parentW = ((android.view.View) getParent()).getWidth();
            }
            flp.height = mPortraitRegion;
            flp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            if (vw > 0 && vh > 0) {
                float aspect = (float) vw / (float) vh;
                // 1.5~2.6 之间(3:2 / 16:9 / 2:1 / 2.35:1 / 2.4:1)按覆盖铺满, 左右裁掉的部分
                // 最多约 30%; 再宽的片源(2.76:1 等)裁得太多, 退回 FIT 保留黑边。
                if (aspect > 1.5f && aspect <= 2.6f && parentW > 0) {
                    int coverW = Math.round(mPortraitRegion * aspect);
                    if (coverW > parentW) flp.width = coverW;
                }
            }
            flp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            v.setLayoutParams(flp);
            v.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 片源比例是播放器解析出来之后才回调的 —— 首次 addDisplay 时 mVideoSize 还是 {0,0},
     * 只能先按 16:9 留位; 拿到真实尺寸后要重算一次, 否则 2.4:1 的片子依旧上下留黑边。
     */
    @Override
    public void onVideoSizeChanged(int videoWidth, int videoHeight) {
        super.onVideoSizeChanged(videoWidth, videoHeight);
        applyPortraitRegion();
    }

    public int[] getVideoSize(){
        return mVideoSize;
    }

    @Override
    public void seekTo(long pos) {
        super.seekTo(pos);
        if (haveDanmu()) danmuView.seekTo(pos);
    }

    @Override
    public void resume() {
        super.resume();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void start() {
        super.start();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void pause() {
        super.pause();
        if (haveDanmu()) danmuView.pause();
    }

    @Override
    public void stopPlay() {
        super.stopPlay();
        if (haveDanmu()) danmuView.stop();
    }

    @Override
    public void release() {
        super.release();
        if (haveDanmu()) danmuView.release();
    }

    private boolean haveDanmu() {
        return danmuView != null && danmuView.isPrepared();
    }

    public void setDanmuView(DanmakuView view) {
        view.setCallback(this);
        danmuView = view;
    }
    public DanmakuView getDanmuView() {
        return danmuView;
    }

    @Override
    public void prepared() {
        App.post(() -> {
            if (danmuView == null) return;
            if (isPlaying() && danmuView.isPrepared()) danmuView.start(getCurrentPosition());
        });
    }

    @Override
    public void updateTimer(DanmakuTimer timer) {

    }

    @Override
    public void danmakuShown(BaseDanmaku danmaku) {

    }

    @Override
    public void drawingFinished() {

    }
}
