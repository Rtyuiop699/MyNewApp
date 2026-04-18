package com.saber.supervc;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.saber.supervc.USB_PERMISSION";

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvConsoleLogs;
    private View welcomeOverlay;
    private View dashboardLayout;

    private HandLandmarker handLandmarker;
    private ExecutorService backgroundExecutor;
    private SerialManager serialManager;

    private String currentScript = "";
    private boolean isVisionMode = false;
    private boolean isTerminalMode = false;
    private static boolean hasShownWelcome = false;
    private int lastFingers = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showDashboard();

        backgroundExecutor = Executors.newSingleThreadExecutor();
        serialManager = new SerialManager();

        // تحميل نموذج MediaPipe
        setupHandLandmarker();

        // تسجيل الـ BroadcastReceiver الخاص بالـ USB
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // طلب إذن USB عند بدء التطبيق
        requestUsbPermission();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isVisionMode || isTerminalMode) {
                    showDashboard();
                } else {
                    finish();
                }
            }
        });
    }

    private void showDashboard() {
        isVisionMode = false;
        isTerminalMode = false;
        setContentView(R.layout.activity_main);

        welcomeOverlay = findViewById(R.id.welcome_overlay);
        dashboardLayout = findViewById(R.id.dashboard_layout);

        if (hasShownWelcome) {
            if (welcomeOverlay != null) welcomeOverlay.setVisibility(View.GONE);
            if (dashboardLayout != null) dashboardLayout.setVisibility(View.VISIBLE);
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (welcomeOverlay != null) welcomeOverlay.setVisibility(View.GONE);
                if (dashboardLayout != null) dashboardLayout.setVisibility(View.VISIBLE);
                hasShownWelcome = true;
            }, 3000);
        }

        findViewById(R.id.card_mediapipe).setOnClickListener(v -> openVisionMode());
        findViewById(R.id.card_terminal).setOnClickListener(v -> openTerminalMode());
    }

    private void requestUsbPermission() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                PendingIntent permissionIntent = PendingIntent.getBroadcast(
                        this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
                );
                usbManager.requestPermission(device, permissionIntent);
            }
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        Log.d("MainActivity", "USB Permission Granted for device: " + device.getDeviceName());
                        if (serialManager.open(context)) {
                            Toast.makeText(context, "Arduino Connected!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Failed to connect Arduino", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    Log.d("MainActivity", "USB Permission Denied");
                    Toast.makeText(context, "USB Permission Denied", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    private void setupHandLandmarker() {
        backgroundExecutor.execute(() -> {
            try {
                BaseOptions baseOptions = BaseOptions.builder()
                        .setModelAssetPath("hand_landmarker.task")
                        .build();

                HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener(this::returnLivestreamResult)
                        .setNumHands(2)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .build();

                handLandmarker = HandLandmarker.createFromOptions(this, options);
            } catch (Exception e) {
                Log.e("SaberVC", "HandLandmarker failed to load", e);
            }
        });
    }

    private void openVisionMode() {
        isVisionMode = true;
        // هنا يتم تحميل واجهة الرؤية وتفعيل الكاميرا
        Toast.makeText(this, "Vision Mode Started", Toast.LENGTH_SHORT).show();
    }

    private void openTerminalMode() {
        isTerminalMode = true;
        // هنا يتم تحميل واجهة Terminal
        Toast.makeText(this, "Terminal Mode Started", Toast.LENGTH_SHORT).show();
    }

    private void returnLivestreamResult(HandLandmarkerResult result, MPImage image) {
        // منطق معالجة النتائج وإرسال البيانات للـ Arduino
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e("MainActivity", "Receiver not registered");
        }
        if (backgroundExecutor != null) backgroundExecutor.shutdown();
        if (serialManager != null) serialManager.close();
        if (handLandmarker != null) {
            try {
                handLandmarker.close();
            } catch (Exception e) {
                Log.e("SaberVC", "Error closing handLandmarker", e);
            }
        }
    }
}
