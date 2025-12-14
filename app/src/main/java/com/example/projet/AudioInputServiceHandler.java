package com.example.projet;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

import java.util.UUID;

public class AudioInputServiceHandler {

    private static final String TAG = "AudioInputHandler";

    // UUIDs du service et des caractéristiques principales
    private static final UUID AUDIO_INPUT_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID   = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_GAIN_UUID    = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE_UUID    = UUID.fromString("00002B75-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID                = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final UiController uiController;

    public AudioInputServiceHandler(Context context, UiController uiController) {
        this.context = context;
        this.uiController = uiController;
    }

    // --- Découverte du service ---
    public void onServiceDiscovered(BluetoothGatt gatt) {
        BluetoothGattService audioService = gatt.getService(AUDIO_INPUT_SERVICE_UUID);
        if (audioService != null) {
            // État audio (notifications)
            BluetoothGattCharacteristic stateChar = audioService.getCharacteristic(AUDIO_INPUT_STATE_UUID);
            if (stateChar != null) {
                subscribeToCharacteristic(gatt, stateChar);
            }

            // Gain (lecture initiale)
            BluetoothGattCharacteristic gainChar = audioService.getCharacteristic(AUDIO_INPUT_GAIN_UUID);
            if (gainChar != null) {
                readCharacteristic(gatt, gainChar);
            }

            // Type (lecture initiale)
            BluetoothGattCharacteristic typeChar = audioService.getCharacteristic(AUDIO_INPUT_TYPE_UUID);
            if (typeChar != null) {
                readCharacteristic(gatt, typeChar);
            }
        } else {
            uiController.updateAudioStatus("Service Audio Input non trouvé");
        }
    }

    // --- Abonnement aux notifications ---
    private void subscribeToCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        // Vérifier permission BLUETOOTH_CONNECT
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permission BLUETOOTH_CONNECT non accordée, abonnement ignoré");
            return;
        }

        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            }
            // API 34+ : use writeDescriptor(descriptor, value)
            gatt.writeDescriptor(descriptor, value);
            Log.d(TAG, "Souscription aux notifications Audio Input envoyée.");
        } else {
            Log.e(TAG, "CCCD non trouvé pour Audio Input.");
        }
    }

    // --- Lecture d’une caractéristique ---
    private void readCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permission BLUETOOTH_CONNECT non accordée, lecture ignorée");
            return;
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            gatt.readCharacteristic(characteristic);
        }
    }

    // --- Traitement des valeurs reçues ---
    public void handleCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value == null) return;
        UUID uuid = characteristic.getUuid();
        String info = "Valeur inconnue";

        if (AUDIO_INPUT_STATE_UUID.equals(uuid)) {
            info = "État Audio: " + (value[0] & 0xFF);
        } else if (AUDIO_INPUT_GAIN_UUID.equals(uuid)) {
            info = "Gain: " + (value[0] & 0xFF);
        } else if (AUDIO_INPUT_TYPE_UUID.equals(uuid)) {
            info = "Type: " + (value[0] & 0xFF);
        }

        uiController.updateAudioStatus(info);
    }
}
