package com.saber.supervc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

public class UsbPermissionReceiver extends BroadcastReceiver {
    private static final String TAG = "UsbPermissionReceiver";
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_USB_PERMISSION.equals(action)) {
            synchronized (this) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        Log.d(TAG, "Permission granted for device: " + device.getDeviceName());
                        // يمكنك إعادة محاولة الاتصال هنا
                    }
                } else {
                    Log.d(TAG, "Permission denied for device");
                }
            }
        }
    }
}
