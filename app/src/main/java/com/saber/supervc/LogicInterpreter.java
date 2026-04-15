package com.saber.supervc;

import android.util.Log;

public class LogicInterpreter {
    private static final String TAG = "LogicInterpreter";
    
    public static void evaluate(int fingers, String script, SerialManager serialManager) {
        if (script == null || script.isEmpty()) {
            return;
        }
        
        String[] lines = script.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            
            // تفسير الأوامر
            if (line.startsWith("if fingers == ")) {
                try {
                    // استخراج الرقم من الشرط
                    String[] parts = line.split("==");
                    if (parts.length > 1) {
                        String conditionPart = parts[1].trim();
                        int targetFingers = Integer.parseInt(conditionPart.split(" ")[0]);
                        
                        if (fingers == targetFingers) {
                            // البحث عن أمر send في نفس السطر أو السطر التالي
                            if (line.contains("send")) {
                                String sendCmd = extractSendCommand(line);
                                if (sendCmd != null && serialManager != null) {
                                    serialManager.sendCommand(sendCmd);
                                    Log.d(TAG, "Executed: " + sendCmd);
                                }
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing fingers: " + e.getMessage());
                }
            }
            else if (line.startsWith("send ")) {
                String command = line.substring(5).trim();
                if (serialManager != null) {
                    serialManager.sendCommand(command);
                    Log.d(TAG, "Sending: " + command);
                }
            }
        }
    }
    
    private static String extractSendCommand(String line) {
        int sendIndex = line.indexOf("send");
        if (sendIndex != -1) {
            return line.substring(sendIndex + 5).trim();
        }
        return null;
    }
}
