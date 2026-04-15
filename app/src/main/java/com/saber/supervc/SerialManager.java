package com.saber.supervc;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SerialManager {
    private static final String TAG = "SerialManager";
    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private SerialInputOutputManager ioManager;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isConnected = false;
    private OnDataReceivedListener dataListener;
    
    public interface OnDataReceivedListener {
        void onDataReceived(String data);
    }
    
    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        this.dataListener = listener;
    }
    
    public boolean open(Context context) {
        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            
            if (drivers.isEmpty()) {
                Log.e(TAG, "No USB devices found");
                return false;
            }
            
            UsbSerialDriver driver = drivers.get(0);
            UsbDevice device = driver.getDevice();
            
            if (!manager.hasPermission(device)) {
                Log.e(TAG, "USB permission not granted");
                return false;
            }
            
            port = driver.getPorts().get(0);
            connection = manager.openDevice(device);
            
            if (connection == null) {
                Log.e(TAG, "Failed to open connection");
                return false;
            }
            
            port.open(connection);
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            
            // إعداد مستمع البيانات
            ioManager = new SerialInputOutputManager(port, new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    final String received = new String(data);
                    Log.d(TAG, "Received: " + received);
                    if (dataListener != null) {
                        dataListener.onDataReceived(received);
                    }
                }
                
                @Override
                public void onRunError(Exception e) {
                    Log.e(TAG, "Serial error: " + e.getMessage());
                }
            });
            
            executor.submit(ioManager);
            isConnected = true;
            Toast.makeText(context, "Arduino Connected!", Toast.LENGTH_SHORT).show();
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
            return false;
        }
    }
    
    public void sendCommand(String command) {
        if (port != null && isConnected) {
            try {
                port.write((command + "\n").getBytes(), 1000);
                Log.d(TAG, "Sent: " + command);
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "Cannot send: not connected");
        }
    }
    
    public void close() {
        if (ioManager != null) {
            ioManager.stop();
        }
        if (port != null) {
            try {
                port.close();
            } catch (IOException e) {
                Log.e(TAG, "Close error: " + e.getMessage());
            }
        }
        if (connection != null) {
            connection.close();
        }
        executor.shutdown();
        isConnected = false;
    }
    
    public boolean isConnected() {
        return isConnected;
    }
}
