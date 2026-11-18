package cn.weiguang.capturefixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Only synthetic content. Changes originate from a different app and cross the Android service boundary. */
public class CaptureFixtureActivity extends Activity {
    private ChatView chat;
    private boolean newMessage;
    private boolean scrolled;
    private boolean otherChat;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep system bars visible; the emulator's first-use immersive coachmark must not cover this fixture.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout root = new FrameLayout(this);
        chat = new ChatView(); root.addView(chat);
        LinearLayout controls = new LinearLayout(this);
        button(controls, "新消息", () -> { newMessage = true; changed(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); });
        button(controls, "滚动", () -> { scrolled = true; changed(AccessibilityEvent.TYPE_VIEW_SCROLLED); });
        button(controls, "重复", () -> changed(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        button(controls, "切聊天", () -> { otherChat = !otherChat; changed(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); });
        button(controls, "安全页", () -> {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            changed(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        });
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(48), android.view.Gravity.BOTTOM);
        root.addView(controls, bottom); setContentView(root);
    }

    private void button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(12);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, -1, 1f));
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private void changed(int event) { chat.invalidate(); chat.postDelayed(() -> chat.sendAccessibilityEvent(event), 80); }

    private class ChatView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ChatView() { super(CaptureFixtureActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            paint.setColor(Color.BLACK); paint.setTextSize(getWidth() * 0.050f);
            if (otherChat) {
                canvas.drawText("无关聊天代号C909", getWidth()*.10f, getHeight()*.34f, paint);
                return;
            }
            canvas.drawText("12:00", getWidth()*.04f, getHeight()*.04f, paint);
            canvas.drawText("小林", getWidth()*.20f, getHeight()*.12f, paint);
            paint.setTextSize(getWidth()*.041f);
            canvas.drawText("12:00", getWidth()*.42f, getHeight()*.23f, paint);
            paint.setTextSize(getWidth()*.050f);
            canvas.drawText("周五在A302讨论项目", getWidth()*.10f, getHeight()*.34f, paint);
            canvas.drawText("记得带电脑", getWidth()*.10f, getHeight()*.44f, paint);
            if (newMessage) canvas.drawText("小林今天不来了", getWidth()*.10f, getHeight()*.58f, paint);
            if (scrolled) canvas.drawText("地点改到B201", getWidth()*.10f, getHeight()*.72f, paint);
        }
    }
}
