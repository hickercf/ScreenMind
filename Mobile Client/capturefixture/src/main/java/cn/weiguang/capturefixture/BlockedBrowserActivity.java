package cn.weiguang.capturefixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** A whitelisted app's non-chat window must still be excluded by the service. */
public class BlockedBrowserActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("合成网页，不能作为聊天上传");
        text.setTextSize(28);
        setContentView(text);
    }
}
