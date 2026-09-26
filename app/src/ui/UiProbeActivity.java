package dev.xr.rayneo.probe.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.xr.rayneo.probe.R;

/**
 * Stage 3.0 build probe: proves the pipeline compiles subpackages and resolves R.* from XML
 * resources. Not exported and not reachable from any existing entry point.
 */
public final class UiProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.ui_probe);
        TextView title = (TextView) findViewById(R.id.ui_probe_title);
        title.setTextColor(getColor(R.color.ui_probe_text));
    }
}
