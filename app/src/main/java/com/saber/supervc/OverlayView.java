package com.saber.supervc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.List;

public class OverlayView extends View {
    private HandLandmarkerResult results;
    private Paint linePaint = new Paint();
    private Paint pointPaint = new Paint();

    // تعريف يدوي لوصلات الأصابع (لتجنب أخطاء المكتبة)
    private static final int[][] CONNECTIONS = {
        {0, 1}, {1, 2}, {2, 3}, {3, 4},       // الإبهام
        {0, 5}, {5, 6}, {6, 7}, {7, 8},       // السبابة
        {9, 10}, {10, 11}, {11, 12},          // الوسطى
        {13, 14}, {14, 15}, {15, 16},         // البنصر
        {0, 17}, {17, 18}, {18, 19}, {19, 20}, // الخنصر
        {5, 9}, {9, 13}, {13, 17}             // راحة اليد
    };

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        linePaint.setColor(Color.CYAN);
        linePaint.setStrokeWidth(8f);
        linePaint.setAntiAlias(true);

        pointPaint.setColor(Color.YELLOW);
        pointPaint.setStrokeWidth(15f);
        pointPaint.setStrokeCap(Paint.Cap.ROUND); // لجعل النقاط دائرية
        pointPaint.setAntiAlias(true);
    }

    public void setResults(HandLandmarkerResult results) {
        this.results = results;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (results == null || results.landmarks() == null) return;

        for (List<NormalizedLandmark> landmarks : results.landmarks()) {
            // رسم الخطوط بناءً على المصفوفة اليدوية
            for (int[] connection : CONNECTIONS) {
                NormalizedLandmark start = landmarks.get(connection[0]);
                NormalizedLandmark end = landmarks.get(connection[1]);

                canvas.drawLine(
                    start.x() * getWidth(), start.y() * getHeight(),
                    end.x() * getWidth(), end.y() * getHeight(),
                    linePaint
                );
            }

            // رسم النقاط الدائرية
            for (NormalizedLandmark landmark : landmarks) {
                canvas.drawPoint(landmark.x() * getWidth(), landmark.y() * getHeight(), pointPaint);
            }
        }
    }
}
