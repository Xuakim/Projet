package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.UUID;

public class AudioInputServiceHandler {

    private static final String TAG = "AICS_HANDLER";

    private final Context context;
    private final UiController uiController;

    // UUID du service AICS
    private static final UUID AICS_SERVICE = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");

    // UUID des caractéristiques AICS
    private static final UUID AUDIO_INPUT_STATE = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTINGS_PROPERTIES = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_CONTROL_POINT = UUID.fromString("00002B7B-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");

    public AudioInputServiceHandler(Context context, UiController uiController) {
        this.context = context;
        this.uiController = uiController;
    }

    /** Appelé quand les services sont découverts */
    public void onServiceDiscovered(BluetoothGatt gatt) {
        BluetoothGattService aics = gatt.getService(AICS_SERVICE);
        if (aics != null) {
            Log.d(TAG, "Service AICS trouvé");

            readCharacteristic(gatt, aics.getCharacteristic(AUDIO_INPUT_STATE), "Audio Input State");
            readCharacteristic(gatt, aics.getCharacteristic(GAIN_SETTINGS_PROPERTIES), "Gain Settings Properties");
            readCharacteristic(gatt, aics.getCharacteristic(AUDIO_INPUT_TYPE), "Audio Input Type");
            readCharacteristic(gatt, aics.getCharacteristic(AUDIO_INPUT_STATUS), "Audio Input Status");
            readCharacteristic(gatt, aics.getCharacteristic(AUDIO_INPUT_CONTROL_POINT), "Audio Input Control Point");
            readCharacteristic(gatt, aics.getCharacteristic(AUDIO_INPUT_DESCRIPTION), "Audio Input Description");
        } else {
            Log.w(TAG, "Service AICS non trouvé");
        }
    }

    /** Lecture d’une caractéristique */
    private void readCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, String label) {
        if (characteristic != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    boolean result = gatt.readCharacteristic(characteristic);
                    Log.d(TAG, "Lecture de " + label + " : " + result);
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission refusée pour lire " + label, e);
                    uiController.showMessage("Impossible de lire " + label + " : permission refusée");
                }
            } else {
                Log.w(TAG, "Permission BLUETOOTH_CONNECT manquante pour " + label);
                uiController.showMessage("Permission manquante pour lire " + label);
            }
        } else {
            Log.w(TAG, "Caractéristique " + label + " non disponible");
        }
    }


    /** Traitement des valeurs lues */
    public void handleCharacteristic(BluetoothGattCharacteristic characteristic) {
        UUID uuid = characteristic.getUuid();
        String value = parseValue(characteristic);

        if (uuid.equals(AUDIO_INPUT_STATE)) {
            uiController.updateAicsValue("Audio Input State", value);
        } else if (uuid.equals(GAIN_SETTINGS_PROPERTIES)) {
            uiController.updateAicsValue("Gain Settings Properties", value);
        } else if (uuid.equals(AUDIO_INPUT_TYPE)) {
            uiController.updateAicsValue("Audio Input Type", value);
        } else if (uuid.equals(AUDIO_INPUT_STATUS)) {
            uiController.updateAicsValue("Audio Input Status", value);
        } else if (uuid.equals(AUDIO_INPUT_CONTROL_POINT)) {
            uiController.updateAicsValue("Audio Input Control Point", value);
        } else if (uuid.equals(AUDIO_INPUT_DESCRIPTION)) {
            uiController.updateAicsValue("Audio Input Description", value);
        }
    }

    /** Conversion brute des bytes en texte lisible */
    private String parseValue(BluetoothGattCharacteristic characteristic) {
        byte[] data = characteristic.getValue();
        if (data == null) return "N/A";

        // Exemple simple : afficher les bytes en hexadécimal
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
