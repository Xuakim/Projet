package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.UUID;

public class BleManager {

    private static final String TAG = "BLE_MANAGER";

    private final Context context;
    private final UiController uiController;
    private BluetoothGatt bluetoothGatt;

    private final AudioInputServiceHandler aicsHandler;

    // UUID du service MCS
    private static final UUID MCS_SERVICE = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    // UUID de la caractéristique Mute (MCS)
    private static final UUID MCS_MUTE = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    // UUID du descripteur CCCD (Client Characteristic Configuration Descriptor)
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public BleManager(Context context, UiController uiController) {
        this.context = context;
        this.uiController = uiController;
        this.aicsHandler = new AudioInputServiceHandler(context, uiController);
    }

    /** Connexion à un périphérique BLE */
    public void connectToDevice(BluetoothDevice device) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothGatt = device.connectGatt(context, false, gattCallback);
                uiController.showMessage("Connexion en cours à " +
                        (device.getName() != null ? device.getName() : "Appareil inconnu"));
            } catch (SecurityException e) {
                uiController.showMessage("Connexion refusée : permission manquante");
            }
        } else {
            uiController.showMessage("Permission BLUETOOTH_CONNECT non accordée");
        }
    }

    /** Déconnexion */
    public void disconnect() {
        if (bluetoothGatt != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    bluetoothGatt.disconnect();
                    bluetoothGatt.close();
                    uiController.showMessage("Déconnecté");
                } catch (SecurityException e) {
                    uiController.showMessage("Déconnexion refusée : permission manquante");
                }
            } else {
                uiController.showMessage("Permission BLUETOOTH_CONNECT manquante pour déconnexion");
            }
            bluetoothGatt = null;
        }
    }

    /** Écriture sur la caractéristique Mute (MCS) */
    public void writeMute(boolean unmute) {
        if (bluetoothGatt == null) return;
        BluetoothGattService mcs = bluetoothGatt.getService(MCS_SERVICE);
        if (mcs == null) return;

        BluetoothGattCharacteristic muteChar = mcs.getCharacteristic(MCS_MUTE);
        if (muteChar == null) return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                // 0x01 = Unmute, 0x00 = Mute
                byte[] value = new byte[]{ (byte)(unmute ? 0x01 : 0x00) };
                muteChar.setValue(value);
                bluetoothGatt.writeCharacteristic(muteChar);
            } catch (SecurityException e) {
                uiController.showMessage("Impossible d'écrire mute : permission refusée");
            }
        } else {
            uiController.showMessage("Permission BLUETOOTH_CONNECT manquante pour mute");
        }
    }

    /** Callback GATT principal */
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        Log.d(TAG, "Connecté au périphérique");
                        uiController.showMessage("Connecté");
                        gatt.discoverServices();
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        Log.d(TAG, "Déconnecté du périphérique");
                        uiController.showMessage("Déconnecté");
                    }
                } catch (SecurityException e) {
                    uiController.showMessage("Erreur de connexion : permission refusée");
                }
            } else {
                uiController.showMessage("Permission BLUETOOTH_CONNECT manquante pour gérer l'état de connexion");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services découverts");
                // Vérifier MCS
                BluetoothGattService mcs = gatt.getService(MCS_SERVICE);
                if (mcs != null) {
                    BluetoothGattCharacteristic muteChar = mcs.getCharacteristic(MCS_MUTE);
                    if (muteChar != null) {
                        safeReadCharacteristic(gatt, muteChar, "Mute (MCS)");
                        enableNotifications(gatt, muteChar);
                    }
                }
                // Vérifier AICS
                aicsHandler.onServiceDiscovered(gatt);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleMuteOrAics(characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            handleMuteOrAics(characteristic);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleMuteOrAics(characteristic);
            }
        }
    };

    /** Lecture sécurisée d’une caractéristique */
    private void safeReadCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, String label) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                gatt.readCharacteristic(characteristic);
                Log.d(TAG, "Lecture de " + label);
            } catch (SecurityException e) {
                uiController.showMessage("Impossible de lire " + label + " : permission refusée");
            }
        } else {
            uiController.showMessage("Permission BLUETOOTH_CONNECT manquante pour " + label);
        }
    }

    /** Activer les notifications pour une caractéristique */
    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                gatt.setCharacteristicNotification(characteristic, true);
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
            } catch (SecurityException e) {
                uiController.showMessage("Impossible d'activer les notifications : permission refusée");
            }
        }
    }

    /** Gestion commune des caractéristiques Mute et AICS */
    private void handleMuteOrAics(BluetoothGattCharacteristic characteristic) {
        UUID uuid = characteristic.getUuid();
        byte[] value = characteristic.getValue();
        if (uuid.equals(MCS_MUTE)) {
            if (value != null && value.length > 0) {
                boolean isUnmuted = (value[0] == 0x01);
                uiController.updateMicStatus(isUnmuted ? "Unmuted" : "Muted");
            }
        } else {
            aicsHandler.handleCharacteristic(characteristic);
        }
    }

    /** Conversion simple des bytes en hexadécimal */
    private String parseBytes(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
