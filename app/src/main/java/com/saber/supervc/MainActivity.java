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
    private EditText logicInput;
    private Button btnSaveLogic;

    private HandLandmarker handLandmarker;
    private ExecutorService backgroundExecutor;
    private SerialManager serialManager;

    private boolean isVisionMode = false;
    private boolean isTerminalMode = false;
    private static boolean hasShownWelcome = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showDashboard();

        backgroundExecutor = Executors.newSingleThreadExecutor();
        serialManager = new SerialManager();

        // إعداد مستمع لاستقبال البيانات من الأردوينو وعرضها في الـ Console
        serialManager.setOnDataReceivedListener(data -> {
            runOnUiThread(() -> {
                if (tvConsoleLogs != null) {
                    tvConsoleLogs.append("\nRDX: " + data.trim());
                }
            });
        });

        setupHandLandmarker();

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

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

        View welcomeOverlay = findViewById(R.id.welcome_overlay);
        View dashboardLayout = findViewById(R.id.dashboard_layout);

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

    private void openVisionMode() {
        isVisionMode = true;
        setContentView(R.layout.cameravisionlayout);
        
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        tvConsoleLogs = findViewById(R.id.tv_console_logs);
        
        startCamera();
    }

    private void openTerminalMode() {
        isTerminalMode = true;
        setContentView(R.layout.terminal_layout);
        
        logicInput = findViewById(R.id.logic_input);
        btnSaveLogic = findViewById(R.id.btn_save_logic);
        
        if (btnSaveLogic != null) {
            btnSaveLogic.setOnClickListener(v -> {
                String logic = logicInput.getText().toString();
                if (!logic.isEmpty() && serialManager != null && serialManager.isConnected()) {
                    serialManager.sendCommand(logic); 
                    Toast.makeText(this, "Logic Sent to Arduino", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Arduino not connected", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void startCamera() {
        Log.d("SaberVC", "Starting CameraX implementation...");
        // هنا يتم إضافة كود ProcessCameraProvider لاحقاً
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
                    if (device != null && serialManager.open(context)) {
                        Log.d("SaberVC", "USB Permission Granted & Port Opened");
                    }
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
                        .build();

                handLandmarker = HandLandmarker.createFromOptions(this, options);
            } catch (Exception e) {
                Log.e("SaberVC", "MediaPipe Init Error", e);
            }
        });
    }

    private void returnLivestreamResult(HandLandmarkerResult result, MPImage image) {
        // منطق معالجة النقاط وإرسال الأوامر عبر serialManager.sendCommand()
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbReceiver); } catch (Exception e) {}
        if (backgroundExecutor != null) backgroundExecutor.shutdown();
        if (serialManager != null) serialManager.close();
        if (handLandmarker != null) handLandmarker.close();
    }
}
