package com.saber.supervc;

import android.Manifest;
import android.content.pm.PackageManager;
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
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

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

    private void openVisionMode() {
        isVisionMode = true;
        setContentView(R.layout.camera_vision_layout);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        tvConsoleLogs = findViewById(R.id.tv_console_logs);

        updateLogs("> Initializing Camera...");

        // فتح الأردوينو
        if (serialManager.open(this)) {
            updateLogs("> Arduino: Connected (9600)");
        } else {
            updateLogs("> Arduino: Not Found / Waiting for OTG");
        }

        updateLogs("> Starting Vision Engine...");
        checkCameraPermissionAndStart();
    }

    private void openTerminalMode() {
        isTerminalMode = true;
        setContentView(R.layout.terminal_layout);
        EditText input = findViewById(R.id.logic_input);
        Button save = findViewById(R.id.btn_save_logic);

        if (input != null) input.setText(currentScript);
        if (save != null) {
            save.setOnClickListener(v -> {
                currentScript = input.getText().toString();
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                showDashboard();
            });
        }
    }

    private void updateLogs(String message) {
        runOnUiThread(() -> {
            if (tvConsoleLogs != null) {
                String currentText = tvConsoleLogs.getText().toString();
                tvConsoleLogs.setText(message + "\n" + (currentText.length() > 500 ? currentText.substring(0, 500) : currentText));
            }
            Log.d("SaberVC", message);
        });
    }

    private void setupHandLandmarker() {
        backgroundExecutor.execute(() -> {
            try {
                updateLogs("> Loading MediaPipe model...");
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
                updateLogs("> MediaPipe model loaded successfully!");
            } catch (Exception e) {
                updateLogs("> MediaPipe Error: " + e.getMessage());
                Log.e("SaberVC", "MP Error", e);
            }
        });
    }

    private void checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateLogs("> Requesting camera permission...");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
        } else {
            updateLogs("> Camera permission granted");
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateLogs("> Camera permission granted!");
                startCamera();
            } else {
                updateLogs("> Camera permission denied!");
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCamera() {
        updateLogs("> Starting camera...");
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(backgroundExecutor, imageProxy -> {
                    if (handLandmarker != null) {
                        try {
                            MPImage mpImage = new BitmapImageBuilder(imageProxy.toBitmap()).build();
                            ImageProcessingOptions options = ImageProcessingOptions.builder()
                                    .setRotationDegrees(imageProxy.getImageInfo().getRotationDegrees())
                                    .build();
                            handLandmarker.detectAsync(mpImage, options, System.currentTimeMillis());
                        } catch (Exception e) {
                            Log.e("SaberVC", "Analysis error: " + e.getMessage());
                        }
                    }
                    imageProxy.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
                updateLogs("> Camera active - Show your hand!");

            } catch (Exception e) {
                updateLogs("> Camera Error: " + e.getMessage());
                Log.e("SaberVC", "Camera error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    
private void returnLivestreamResult(HandLandmarkerResult result, MPImage image) {
    if (isVisionMode) {
        runOnUiThread(() -> {

            if (overlayView != null) {
                overlayView.setResults(result);
            }

            if (result.landmarks() != null && !result.landmarks().isEmpty()) {

                int fingers = countFingers(result);

                if (fingers != lastFingers) {
                    lastFingers = fingers;

                    String msg = "Hand State Changed: " + fingers + " finger(s)";
                    updateLogs(msg);

                    LogicInterpreter.evaluate(fingers, currentScript, serialManager);
                }
            }
        });
    }
}

    private int countFingers(HandLandmarkerResult result) {
        return 1;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
