package com.example.projet;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

public class UiController {

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final TextView statusText;
    private final TextView audioInputState;
    private final TextView gainSettings;
    private final TextView audioInputType;
    private final TextView audioInputStatus;
    private final TextView audioInputDescription;

    public UiController(Activity activity) {
        this.activity = activity;
        this.statusText = activity.findViewById(R.id.statusText);
        this.audioInputState = activity.findViewById(R.id.audioInputState);
        this.gainSettings = activity.findViewById(R.id.gainSettings);
        this.audioInputType = activity.findViewById(R.id.audioInputType);
        this.audioInputStatus = activity.findViewById(R.id.audioInputStatus);
        this.audioInputDescription = activity.findViewById(R.id.audioInputDescription);
    }

    public void showMessage(String message) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText(message);
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        });
    }

    public void updateMicStatus(String status) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText("Microphone : " + status);
        });
    }

    public void updateAudioInputState(String text) {
        mainHandler.post(() -> {
            if (audioInputState != null) audioInputState.setText("Audio Input State : " + text);
        });
    }

    public void updateGainSettings(String text) {
        mainHandler.post(() -> {
            if (gainSettings != null) gainSettings.setText("Gain Settings : " + text);
        });
    }

    public void updateAudioInputType(String text) {
        mainHandler.post(() -> {
            if (audioInputType != null) audioInputType.setText("Audio Input Type : " + text);
        });
    }

    public void updateAudioInputStatus(String text) {
        mainHandler.post(() -> {
            if (audioInputStatus != null) audioInputStatus.setText("Audio Input Status : " + text);
        });
    }

    public void updateAudioInputDescription(String text) {
        mainHandler.post(() -> {
            if (audioInputDescription != null) audioInputDescription.setText("Audio Input Description : " + text);
        });
    }
}

