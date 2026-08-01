package io.lanprojects.phone;

import android.app.Activity;
import android.os.Bundle;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * WeChat-style QR scanner. Uses zxing's CaptureManager + DecoratedBarcodeView
 * with our own layout: a back button on the left, a bottom hint, and a custom
 * viewfinder (WeChatViewfinderView) with green corners + a green scan line
 * instead of the default bare laser beam.
 */
public class ScanActivity extends Activity {

    private CaptureManager capture;
    private DecoratedBarcodeView barcodeScannerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        barcodeScannerView = findViewById(R.id.zxing_barcode_scanner);
        capture = new CaptureManager(this, barcodeScannerView);
        capture.initializeFromIntent(getIntent(), savedInstanceState);
        // IMPORTANT: without decode() the decode loop never starts and the scan
        // just sits on the camera preview forever ("扫码连接不上").
        capture.decode();

        findViewById(R.id.btnScanBack).setOnClickListener(v -> finish());

        // targetSdk 35 forces edge-to-edge: push the top bar below the status
        // bar so the back button is not hidden underneath it.
        android.view.View topBar = findViewById(R.id.scanTopBar);
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, 0);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (capture != null) capture.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (capture != null) capture.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (capture != null) capture.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (capture != null) capture.onSaveInstanceState(state);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // Let CaptureManager handle the CAMERA permission result.
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (capture != null) {
            capture.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }
}
