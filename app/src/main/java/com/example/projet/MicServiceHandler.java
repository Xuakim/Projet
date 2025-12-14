package com.example.projet;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.util.Log;

import java.util.UUID;

public class MicServiceHandler {

    private static final String TAG = "MicServiceHandler";

    // UUIDs du service et de la caractéristique Mute
    private static final UUID MIC_SERVICE_UUID = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    private static final UUID MIC_MUTE_UUID   = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final BleManager bleManager;
    private final UiController uiController;

    public MicServiceHandler(BleManager bleManager, UiController uiController) {
        this.bleManager = bleManager;
        this.uiController = uiController;
    }

    // --- Découverte du service ---
    public void onServiceDiscovered(BluetoothGatt gatt) {
        BluetoothGattService micService = gatt.getService(MIC_SERVICE_UUID);
        if (micService != null) {
            BluetoothGattCharacteristic muteChar = micService.getCharacteristic(MIC_MUTE_UUID);
            if (muteChar != null) {
                subscribeToMuteCharacteristic(gatt, muteChar);
            } else {
                uiController.updateMicStatus("Caractéristique Mute non trouvée");
            }
        } else {
            uiController.updateMicStatus("Service Microphone non trouvé");
        }
    }

    // --- Abonnement aux notifications ---
    private void subscribeToMuteCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value);
            } else {
                descriptor.setValue(value);
                gatt.writeDescriptor(descriptor);
            }
            Log.d(TAG, "Souscription aux notifications Mute envoyée.");
        } else {
            Log.e(TAG, "CCCD non trouvé pour la caractéristique Mute.");
        }
    }

    // --- Traitement des valeurs reçues ---
    public void handleCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (MIC_MUTE_UUID.equals(characteristic.getUuid())) {
            String info = "Valeur inconnue";
            if (value != null && value.length > 0) {
                switch (value[0] & 0xFF) {
                    case 0: info = "Non muet"; break;
                    case 1: info = "Muet"; break;
                    case 2: info = "Sourdine désactivée"; break;
                    default: info = "Valeur inconnue: " + (value[0] & 0xFF); break;
                }
            }
            uiController.updateMicStatus(info);
        }
    }
}

