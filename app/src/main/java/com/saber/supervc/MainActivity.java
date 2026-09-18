package com.saber.supervc;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.List;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
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
import androidx.camera.core.ImageProxy;
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

    private static final String TAG = "SaberVC";
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
    private ProcessCameraProvider cameraProvider;
   // متغير لمتابعة الكاميرا الحالية (الافتراضي: الأمامية)
private CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
private ImageButton btnSwitchCamera;
    
    private boolean isVisionMode = false;
    private boolean isTerminalMode = false;
    private static boolean hasShownWelcome = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check for permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }

        backgroundExecutor = Executors.newSingleThreadExecutor();
        serialManager = new SerialManager();

        setupHandLandmarker();
        registerUsbReceiver();
        requestUsbPermission();

        showDashboard();

        // Handle Back Press
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isVisionMode || isTerminalMode) {
                    stopCamera();
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

        View cardMediaPipe = findViewById(R.id.card_mediapipe);
        View cardTerminal = findViewById(R.id.card_terminal);

        if (cardMediaPipe != null) cardMediaPipe.setOnClickListener(v -> openVisionMode());
        if (cardTerminal != null) cardTerminal.setOnClickListener(v -> openTerminalMode());
    }

    private void openVisionMode() {
    isVisionMode = true;
    isTerminalMode = false;
    setContentView(R.layout.camera_vision_layout);

    previewView = findViewById(R.id.previewView);
    overlayView = findViewById(R.id.overlayView);
    tvConsoleLogs = findViewById(R.id.tv_console_logs);

    // ربط زر التبديل والحدث الخاص به
    android.widget.ImageButton btnSwitchCamera = findViewById(R.id.btn_switch_camera);
    if (btnSwitchCamera != null) {
        btnSwitchCamera.setOnClickListener(v -> switchCamera());
    }

    startCamera();
}

// دالة التبديل بين الكاميرات وإعادة تشغيل البث
private void switchCamera() {
    if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    } else {
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
    }
    
    // إعادة بناء الكاميرا مع الاتجاه الجديد
    startCamera();
}
    
private int countFingers(NormalizedLandmarkList landmarks) {
    int count = 0;

    // الإبهام: مقارنة الإحداثي السيني X لطرف الإبهام مع المفصل
    float thumbTipX = landmarks.get(4).getX();
    float thumbIpX = landmarks.get(3).getX();
    // إذا كان الإبهام يتجه للخارج
    if (Math.abs(thumbTipX - thumbIpX) > 0.04) {
        count++;
    }

    // الأصابع الأربعة (السبابة، الوسطى، البنصر، الخنصر)
    // مقارنة الإحداثي الصادي Y (ملاحظة: Y ينقص كلما اتجهنا للأعلى في الشاشة)
    int[] fingerTipIds = {8, 12, 16, 20};  // أطراف الأصابع
    int[] fingerPipIds = {6, 10, 14, 18};  // مفاصل الأصابع المتوسطة

    for (int i = 0; i < fingerTipIds.length; i++) {
        float tipY = landmarks.get(fingerTipIds[i]).getY();
        float pipY = landmarks.get(fingerPipIds[i]).getY();

        if (tipY < pipY) { // الطرف أعلى من المفصل
            count++;
        }
    }

    return count;
}
 private void switchCamera() {
    // التبديل بين الكاميرا الأمامية والخلفية
    if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    } else {
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
    }
    
    // إعادة تشغيل الكاميرا بالاتجاه الجديد
    startCamera();
 }
    

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                if (previewView != null) {
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                }

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(backgroundExecutor, this::processImageFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Camera Initialization Error: " + e.getMessage(), e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageFrame(ImageProxy image) {
        if (!isVisionMode || handLandmarker == null) {
            image.close();
            return;
        }

        try {
            Bitmap bitmap = image.toBitmap();
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            
            // Rotate bitmap if necessary to align frame correctly
            if (rotationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationDegrees);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            handLandmarker.detectAsync(mpImage, System.currentTimeMillis());

        } catch (Exception e) {
            Log.e(TAG, "Frame Processing Error: " + e.getMessage());
        } finally {
            image.close();
        }
    }

    private void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void openTerminalMode() {
        isTerminalMode = true;
        isVisionMode = false;
        setContentView(R.layout.terminal_layout);

        logicInput = findViewById(R.id.logic_input);
        btnSaveLogic = findViewById(R.id.btn_save_logic);

        if (btnSaveLogic != null) {
            btnSaveLogic.setOnClickListener(v -> {
                if (logicInput == null) return;
                String logic = logicInput.getText().toString().trim();
                if (!logic.isEmpty() && serialManager != null && serialManager.isConnected()) {
                    serialManager.sendCommand(logic);
                    Toast.makeText(this, "Sent to Arduino", Toast.LENGTH_SHORT).show();
                } else if (!serialManager.isConnected()) {
                    Toast.makeText(this, "Arduino not connected", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void setupHandLandmarker() {
    backgroundExecutor.execute(() -> {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build();

            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener((result, image) -> {
                        // تحديث الواجهة على الخيط الرئيسي
                        runOnUiThread(() -> {
                            if (overlayView != null && isVisionMode) {
                                overlayView.setResults(result);
                                overlayView.invalidate();

                                // التحقق من كشف اليد وحساب الأصابع
                                if (result != null && !result.landmarks().isEmpty()) {
                                    // جلب نقاط اليد الأولى
                                    var handLandmarks = result.landmarks().get(0);
                                    
                                    // حساب عدد الأصابع المفتوحة
                                    int openFingers = countFingers(handLandmarks);

                                    // تحديث النص في أسفل الشاشة (System Analysis Log)
                                    if (tvConsoleLogs != null) {
                                        String logText = "> Initializing MediaPipe...\n" +
                                                         "> Searching for Arduino...\n" +
                                                         "> Status: Connected\n" +
                                                         "> Fingers Detected: " + openFingers;
                                        tvConsoleLogs.setText(logText);
                                    }

                                    // إرسال عدد الأصابع تلقائياً لـ Arduino
                                    if (serialManager != null && serialManager.isConnected()) {
                                        serialManager.sendCommand(String.valueOf(openFingers));
                                    }
                                } else {
                                    // في حال عدم وجود يد أمام الكاميرا
                                    if (tvConsoleLogs != null) {
                                        String logText = "> Initializing MediaPipe...\n" +
                                                         "> Searching for Arduino...\n" +
                                                         "> Status: Connected\n" +
                                                         "> Fingers Detected: 0 (No hand)";
                                        tvConsoleLogs.setText(logText);
                                    }
                                }
                            }
                        });
                    })
                    .setNumHands(2)
                    .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            Log.e(TAG, "MediaPipe Initialization Error: " + e.getMessage(), e);
        }
    });
}

// دالة مساعدة لحساب الأصابع المفتوحة (توضع داخل كلاس MainActivity)
private int countFingers(List<NormalizedLandmark> landmarks) {
    if (landmarks == null || landmarks.size() < 21) {
        return 0; // حماية في حال عدم اكتمال النقاط
    }

    int count = 0;

    // 1. الإبهام (مقارنة أفقية X بين الطرف X والمفصل)
    float thumbTipX = landmarks.get(4).x();
    float thumbIpX = landmarks.get(3).x();
    if (Math.abs(thumbTipX - thumbIpX) > 0.04) {
        count++;
    }

    // 2. الأصابع الأربعة (مقارنة عمودية Y بين الطرف والمفصل)
    int[] fingerTipIds = {8, 12, 16, 20};  // أطراف الأصابع
    int[] fingerPipIds = {6, 10, 14, 18};  // المفاصل المتوسطة

    for (int i = 0; i < fingerTipIds.length; i++) {
        float tipY = landmarks.get(fingerTipIds[i]).y();
        float pipY = landmarks.get(fingerPipIds[i]).y();

        if (tipY < pipY) { // الطرف أعلى في الشاشة من المفصل
            count++;
        }
    }

    return count;
}
    
    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
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
                    if (device != null && serialManager != null) {
                        serialManager.open(context);
                    }
                }
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}

        stopCamera();

        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
        }
        if (serialManager != null) {
            serialManager.close();
        }
        if (handLandmarker != null) {
            handLandmarker.close();
        }
    }
    }
    
