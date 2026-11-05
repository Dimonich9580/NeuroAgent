// file: apk/src/org/Dimonich/service/PythonService.java
// target: Android API 33+, python-for-android bootstrap (SDK/SDL2)
//
// Foreground-сервис KAI9000. p4a уже умеет поднимать Python-поток из
// org.kivy.android.PythonService — здесь только поведение поверх:
// STICKY (переживает выгрузку UI), постоянный канал уведомления и
// корректная остановка по stopSelf().
//
// Класс наследует p4a-базу по полному имени: простое имя совпадает.
package org.Dimonich.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public class PythonService extends org.kivy.android.PythonService {

    private static final String TAG = "KAI9000Service";
    private static final String CHANNEL_ID = "org.Dimonich.kai9000";

    @Override
    public int startType() {
        // STICKY: агент должен пережить закрытие активити и добиться
        // рестарта от системы. p4a сам собирает default intent при null.
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        int result = super.onStartCommand(intent, flags, startId);
        Log.i(TAG, "onStartCommand startId=" + startId + " flags=" + flags);
        return result;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "KAI9000 agent",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Фоновый агент KAI9000: метрики и действия");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
