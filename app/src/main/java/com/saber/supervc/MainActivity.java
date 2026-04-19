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
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.saber.supervc.USB_PERMISSION";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }

        showDashboard();

        backgroundExecutor = Executors.newSingleThreadExecutor();
        serialManager = new SerialManager();

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
        setContentView(R.layout.camera_vision_layout);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        tvConsoleLogs = findViewById(R.id.tv_console_logs);

        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(backgroundExecutor, image -> {
                    if (handLandmarker != null) {
                        MPImage mpImage = new BitmapImageBuilder(image.toBitmap()).build();
                        handLandmarker.detectAsync(mpImage, System.currentTimeMillis());
                    }
                    image.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e("SaberVC", "Camera Error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void openTerminalMode() {
        isTerminalMode = true;
        setContentView(R.layout.terminal_layout);
        logicInput = findViewById(R.id.logic_input);
        btnSaveLogic = findViewById(R.id.btn_save_logic);

        if (btnSaveLogic != null) {
            btnSaveLogic.setOnClickListener(v -> {
                String logic = logicInput.getText().toString();
                if (!logic.isEmpty() && serialManager.isConnected()) {
                    serialManager.sendCommand(logic);
                    Toast.makeText(this, "Sent to Arduino", Toast.LENGTH_SHORT).show();
                }
            });
        }
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
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) serialManager.open(context);
                }
            }
        }
    };

    private void setupHandLandmarker() {
        backgroundExecutor.execute(() -> {
            try {
                BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
                HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener((result, image) -> {
                            if (overlayView != null && isVisionMode) {
                                overlayView.setResults(result);
                                runOnUiThread(() -> overlayView.invalidate());
                            }
                        })
                        .setNumHands(2)
                        .build();
                handLandmarker = HandLandmarker.createFromOptions(this, options);
            } catch (Exception e) {
                Log.e("SaberVC", "MediaPipe Error: " + e.getMessage());
            }
        });
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
