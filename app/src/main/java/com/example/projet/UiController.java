package com.example.projet;

import android.app.Activity;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

public class UiController {

    private final Activity activity;
    private final TextView dataDisplay;
    private final ListView deviceListView;

    public UiController(Activity activity, TextView dataDisplay, ListView deviceListView) {
        this.activity = activity;
        this.dataDisplay = dataDisplay;
        this.deviceListView = deviceListView;
    }

    // --- Affichage générique ---
    public void showMessage(String message) {
        activity.runOnUiThread(() -> dataDisplay.setText(message));
    }

    // --- Mise à jour du statut du micro ---
    public void updateMicStatus(String status) {
        activity.runOnUiThread(() -> dataDisplay.setText("Microphone: " + status));
    }

    // --- Mise à jour du statut audio input ---
    public void updateAudioStatus(String status) {
        activity.runOnUiThread(() -> dataDisplay.setText("Audio Input: " + status));
    }

    // --- Contrôle visibilité ---
    public void showDeviceList() {
        activity.runOnUiThread(() -> {
            deviceListView.setVisibility(View.VISIBLE);
            dataDisplay.setVisibility(View.GONE);
        });
    }

    public void showDataDisplay() {
        activity.runOnUiThread(() -> {
            deviceListView.setVisibility(View.GONE);
            dataDisplay.setVisibility(View.VISIBLE);
        });
    }
}
