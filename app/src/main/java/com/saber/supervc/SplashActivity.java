package com.saber.supervc;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash); // شاشة الترحيب (رسالة + صورة)

        // الانتظار 3 ثوانٍ ثم الانتقال إلى الواجهة الرئيسية
        new Handler().postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish(); // إغلاق شاشة الترحيب حتى لا يعود إليها المستخدم بالزر Back
        }, 3000);
    }
}
