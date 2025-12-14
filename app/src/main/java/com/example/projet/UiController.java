package com.example.projet;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.ListView;
import android.widget.TextView;

public class UiController {

    // --- Champs généraux ---
    private final TextView statusText;
    private final ListView deviceListView;

    // --- Champs AICS ---
    private final TextView audioInputState;
    private final TextView gainSettings;
    private final TextView audioInputType;
    private final TextView audioInputStatus;
    private final TextView audioInputControlPoint;
    private final TextView audioInputDescription;

    // Handler pour garantir les mises à jour sur le thread principal
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public UiController(Activity activity, TextView statusText, ListView deviceListView) {
        this.statusText = statusText;
        this.deviceListView = deviceListView;

        // Récupération des TextView AICS depuis le layout
        this.audioInputState = activity.findViewById(R.id.audioInputState);
        this.gainSettings = activity.findViewById(R.id.gainSettings);
        this.audioInputType = activity.findViewById(R.id.audioInputType);
        this.audioInputStatus = activity.findViewById(R.id.audioInputStatus);
        this.audioInputControlPoint = activity.findViewById(R.id.audioInputControlPoint);
        this.audioInputDescription = activity.findViewById(R.id.audioInputDescription);
    }

    // --- Méthodes pour afficher des messages généraux ---
    public void showMessage(String message) {
        if (statusText != null) {
            mainHandler.post(() -> statusText.setText(message));
        }
    }

    // --- Mise à jour du statut du micro (MCS) ---
    public void updateMicStatus(String status) {
        if (statusText != null) {
            mainHandler.post(() -> statusText.setText("Microphone : " + status));
        }
    }

    // --- Mise à jour des caractéristiques AICS ---
    public void updateAicsValue(String label, String value) {
        switch (label) {
            case "Audio Input State":
                if (audioInputState != null) {
                    mainHandler.post(() -> audioInputState.setText("Audio Input State : " + value));
                }
                break;
            case "Gain Settings Properties":
                if (gainSettings != null) {
                    mainHandler.post(() -> gainSettings.setText("Gain Settings Properties : " + value));
                }
                break;
            case "Audio Input Type":
                if (audioInputType != null) {
                    mainHandler.post(() -> audioInputType.setText("Audio Input Type : " + value));
                }
                break;
            case "Audio Input Status":
                if (audioInputStatus != null) {
                    mainHandler.post(() -> audioInputStatus.setText("Audio Input Status : " + value));
                }
                break;
            case "Audio Input Control Point":
                if (audioInputControlPoint != null) {
                    mainHandler.post(() -> audioInputControlPoint.setText("Audio Input Control Point : " + value));
                }
                break;
            case "Audio Input Description":
                if (audioInputDescription != null) {
                    mainHandler.post(() -> audioInputDescription.setText("Audio Input Description : " + value));
                }
                break;
        }
    }
}
