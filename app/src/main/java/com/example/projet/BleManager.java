package com.example.projet;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

public class BleManager {

    private static final String TAG = "BleManager";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BleEventListener listener;
    private int serviceDiscoveryRetries = 0;
    private static final int MAX_SERVICE_DISCOVERY_RETRIES = 3;

    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;

    public static final UUID MCS_SERVICE = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    public static final UUID MCS_MUTE = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Queue<Runnable> bleOperationQueue = new LinkedList<>();
    private boolean isOperationInProgress = false;
    private int writeRetryCount = 0;
    private static final int MAX_WRITE_RETRIES = 3;

    public BleManager(Context context, BleEventListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            this.scanner = adapter.getBluetoothLeScanner();
        } else {
            Log.w(TAG, "BluetoothAdapter null");
        }
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        Log.d(TAG, "startScan called");
        if (scanner == null) {
            notifyError("Scanner BLE non disponible");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                notifyError("Permission BLUETOOTH_SCAN manquante");
                return;
            }
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        try {
            scanner.startScan(null, settings, scanCallback);
            Log.d(TAG, "scanner.startScan invoked (low latency)");
        } catch (SecurityException se) {
            notifyError("Démarrage du scan refusé : permission manquante");
            Log.w(TAG, "startScan SecurityException", se);
        } catch (Exception e) {
            notifyError("Erreur démarrage scan: " + e.getMessage());
            Log.w(TAG, "startScan Exception", e);
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission BLUETOOTH_SCAN manquante pour stopScan");
                return;
            }
        }
        try {
            scanner.stopScan(scanCallback);
            Log.d(TAG, "Scan arrêté");
        } catch (SecurityException se) {
            notifyError("Arrêt du scan refusé : permission manquante");
            Log.w(TAG, "stopScan SecurityException", se);
        } catch (Exception e) {
            notifyError("Erreur arrêt scan: " + e.getMessage());
            Log.w(TAG, "stopScan Exception", e);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            Log.d(TAG, "onScanResult: name=" + name + " addr=" + (device != null ? device.getAddress() : "null"));
            mainHandler.post(() -> listener.onScanResult(device));
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "Scan failed: " + errorCode);
            notifyError("Scan failed: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante");
            return;
        }
        if (device == null) {
            notifyError("Device null");
            return;
        }

        // Si une connexion existe déjà, la fermer d'abord
        if (bluetoothGatt != null) {
            Log.w(TAG, "Closing existing GATT connection before new connect");
            try {
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing previous GATT", e);
            }
            bluetoothGatt = null;
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
            Log.d(TAG, "Tentative de connexion à " + device.getAddress());
        } catch (SecurityException e) {
            notifyError("Connexion refusée : permission manquante");
            Log.w(TAG, "connect SecurityException", e);
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (bluetoothGatt != null) {
            try {
                Log.d(TAG, "Disconnecting GATT...");
                bluetoothGatt.disconnect();

                // Attendre que la déconnexion soit effective avant de fermer
                mainHandler.postDelayed(() -> {
                    try {
                        if (bluetoothGatt != null) {
                            bluetoothGatt.close();
                            Log.d(TAG, "GATT closed properly");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing GATT", e);
                    } finally {
                        bluetoothGatt = null;
                    }
                }, 300);

            } catch (SecurityException e) {
                notifyError("Permission refusée pour déconnecter");
                Log.w(TAG, "disconnect SecurityException", e);
                bluetoothGatt = null;
            }
        }
    }

    public synchronized BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }

    /**
     * Vider le cache GATT pour forcer Android à redécouvrir les services
     * Utilise une API cachée via reflection
     */
    private boolean refreshGattCache(BluetoothGatt gatt) {
        if (gatt == null) return false;
        try {
            java.lang.reflect.Method refresh = gatt.getClass().getMethod("refresh");
            if (refresh != null) {
                boolean result = (boolean) refresh.invoke(gatt);
                Log.d(TAG, "refreshGattCache invoked -> " + result);
                return result;
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshGattCache failed", e);
        }
        return false;
    }

    private void queueBleOperation(Runnable operation) {
        bleOperationQueue.add(operation);
        if (!isOperationInProgress) {
            executeNextOperation();
        }
    }

    private void executeNextOperation() {
        if (bleOperationQueue.isEmpty()) {
            isOperationInProgress = false;
            return;
        }
        isOperationInProgress = true;
        Runnable operation = bleOperationQueue.poll();
        if (operation != null) {
            mainHandler.post(operation);
        }
    }

    private void onOperationCompleted() {
        mainHandler.postDelayed(() -> {
            isOperationInProgress = false;
            executeNextOperation();
        }, 100);
    }

    @SuppressLint("MissingPermission")
    public void safeReadCharacteristic(BluetoothGattCharacteristic characteristic) {
        queueBleOperation(() -> {
            BluetoothGatt gatt = getBluetoothGatt();
            if (gatt == null || characteristic == null) {
                onOperationCompleted();
                return;
            }
            if (!hasConnectPermission()) {
                notifyError("Permission BLUETOOTH_CONNECT manquante pour lecture");
                onOperationCompleted();
                return;
            }
            try {
                boolean ok = gatt.readCharacteristic(characteristic);
                Log.d(TAG, "readCharacteristic " + characteristic.getUuid() + " -> " + ok);
                if (!ok) onOperationCompleted();
            } catch (SecurityException e) {
                notifyError("Lecture caractéristique refusée : permission");
                Log.w(TAG, "readCharacteristic SecurityException", e);
                onOperationCompleted();
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        queueBleOperation(() -> {
            BluetoothGatt gatt = getBluetoothGatt();
            if (gatt == null) {
                Log.w(TAG, "writeCharacteristic: GATT is null");
                onOperationCompleted();
                return;
            }
            if (characteristic == null) {
                Log.w(TAG, "writeCharacteristic: characteristic is null");
                onOperationCompleted();
                return;
            }
            if (!hasConnectPermission()) {
                notifyError("Permission BLUETOOTH_CONNECT manquante pour écriture");
                onOperationCompleted();
                return;
            }

            int props = characteristic.getProperties();
            boolean hasWrite = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
            boolean hasWriteNoResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;

            if (!hasWrite && !hasWriteNoResponse) {
                Log.e(TAG, "writeCharacteristic: Characteristic does not support Write! UUID=" + characteristic.getUuid() + ", Properties=" + props);
                notifyError("Caractéristique non-écriture: " + characteristic.getUuid());
                onOperationCompleted();
                return;
            }

            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            Log.d(TAG, "writeCharacteristic: Using WRITE_TYPE_DEFAULT");

            characteristic.setValue(value);

            try {
                boolean ok = gatt.writeCharacteristic(characteristic);
                Log.d(TAG, "writeCharacteristic " + characteristic.getUuid() + " -> " + ok);

                if (!ok) {
                    Log.e(TAG, "writeCharacteristic FAILED for " + characteristic.getUuid());

                    if (writeRetryCount < MAX_WRITE_RETRIES) {
                        writeRetryCount++;
                        Log.w(TAG, "Retrying write in 300ms (attempt " + writeRetryCount + ")");
                        mainHandler.postDelayed(() -> {
                            writeRetryCount = 0;
                            writeCharacteristic(characteristic, value);
                        }, 300);
                    } else {
                        writeRetryCount = 0;
                        notifyError("Échec écriture après " + MAX_WRITE_RETRIES + " tentatives");
                        onOperationCompleted();
                    }
                } else {
                    writeRetryCount = 0;
                    Log.d(TAG, "writeCharacteristic SUCCESS for " + characteristic.getUuid());
                }
            } catch (SecurityException e) {
                notifyError("Écriture caractéristique refusée : permission");
                Log.w(TAG, "writeCharacteristic SecurityException", e);
                onOperationCompleted();
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void enableNotifications(BluetoothGattCharacteristic characteristic, boolean enable) {
        queueBleOperation(() -> {
            BluetoothGatt gatt = getBluetoothGatt();
            if (gatt == null || characteristic == null) {
                onOperationCompleted();
                return;
            }
            if (!hasConnectPermission()) {
                notifyError("Permission BLUETOOTH_CONNECT manquante pour notifications");
                onOperationCompleted();
                return;
            }
            try {
                gatt.setCharacteristicNotification(characteristic, enable);
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                if (descriptor != null) {
                    writeCccd(gatt, descriptor, enable);
                } else {
                    Log.w(TAG, "CCCD non trouvé pour " + characteristic.getUuid());
                    onOperationCompleted();
                }
            } catch (SecurityException e) {
                notifyError("Activation notifications refusée : permission");
                Log.w(TAG, "enableNotifications SecurityException", e);
                onOperationCompleted();
            }
        });
    }

    private void writeCccd(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, boolean enable) {
        if (gatt == null || descriptor == null) {
            onOperationCompleted();
            return;
        }
        byte[] value = enable ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value);
            } else {
                descriptor.setValue(value);
                gatt.writeDescriptor(descriptor);
            }
            Log.d(TAG, "writeCccd requested for " + descriptor.getUuid());
        } catch (SecurityException e) {
            notifyError("Écriture CCCD refusée : permission");
            Log.w(TAG, "writeCccd SecurityException", e);
            onOperationCompleted();
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = (gatt != null) ? gatt.getDevice() : null;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Connection state change error: status=" + status);
                if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    mainHandler.post(() -> listener.onDisconnected(device));
                }
                return;
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "GATT connecté");
                serviceDiscoveryRetries = 0;

                // Nettoyer le cache GATT pour éviter les problèmes de services obsolètes
                refreshGattCache(gatt);

                mainHandler.post(() -> listener.onConnected(device));

                try {
                    final BluetoothGatt localGatt = gatt;
                    // Délai augmenté pour laisser le temps au serveur de se stabiliser
                    mainHandler.postDelayed(() -> {
                        try {
                            Log.d(TAG, "Lancement delayed de discoverServices()");
                            if (localGatt != null) localGatt.discoverServices();
                        } catch (SecurityException e) {
                            notifyError("discoverServices permission refusée");
                            Log.w(TAG, "discoverServices SecurityException", e);
                        }
                    }, 1000); // 1 seconde pour laisser le temps au serveur
                } catch (SecurityException e) {
                    notifyError("discoverServices permission refusée");
                    Log.w(TAG, "discoverServices SecurityException", e);
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT déconnecté");
                mainHandler.post(() -> listener.onDisconnected(device));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services découverts");
                serviceDiscoveryRetries = 0;
                mainHandler.post(() -> listener.onServicesDiscovered(gatt));
            } else {
                Log.e(TAG, "Services discovery failed: status=" + status);
                if (serviceDiscoveryRetries < MAX_SERVICE_DISCOVERY_RETRIES) {
                    serviceDiscoveryRetries++;
                    Log.w(TAG, "Retrying discoverServices in 500ms (attempt " + serviceDiscoveryRetries + ")");
                    mainHandler.postDelayed(() -> {
                        try {
                            if (bluetoothGatt != null) bluetoothGatt.discoverServices();
                        } catch (SecurityException se) {
                            notifyError("discoverServices retry permission refusée");
                            Log.w(TAG, "discoverServices retry SecurityException", se);
                        }
                    }, 500);
                } else {
                    notifyError("Échec découverte services après " + MAX_SERVICE_DISCOVERY_RETRIES + " tentatives");
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            onOperationCompleted();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> listener.onCharacteristicRead(characteristic));
            } else {
                Log.e(TAG, "Characteristic read failed: status=" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            mainHandler.post(() -> listener.onCharacteristicChanged(characteristic));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            onOperationCompleted();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicWrite SUCCESS for " + characteristic.getUuid());
                mainHandler.post(() -> {
                    notifyError("Écriture réussie !");
                });
            } else {
                Log.e(TAG, "onCharacteristicWrite FAILED for " + characteristic.getUuid() + " status=" + status);
                notifyError("Characteristic write failed: status=" + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            onOperationCompleted();
            Log.d(TAG, "onDescriptorWrite callback uuid=" + (descriptor != null ? descriptor.getUuid() : "null") + " status=" + status);
            mainHandler.post(() -> listener.onDescriptorWrite(descriptor, status));
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            onOperationCompleted();
            Log.d(TAG, "onDescriptorRead callback uuid=" + (descriptor != null ? descriptor.getUuid() : "null") + " status=" + status);
            mainHandler.post(() -> listener.onDescriptorRead(descriptor, status));
        }
    };

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void notifyError(String message) {
        Log.w(TAG, message);
        mainHandler.post(() -> listener.onError(message));
    }

    private String safeName(BluetoothDevice device) {
        if (device == null) return "device";
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                String n = device.getName();
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (SecurityException ignored) { }
        try {
            String addr = device.getAddress();
            if (addr != null && !addr.trim().isEmpty()) return addr;
        } catch (SecurityException ignored) { }
        return "device";
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    @SuppressLint("MissingPermission")
    public boolean createBond(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "createBond: device is null");
            return false;
        }
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante pour bonding");
            return false;
        }

        int bondState = device.getBondState();
        Log.d(TAG, "Current bond state: " + bondState);

        if (bondState == BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "Device already bonded");
            return true;
        }

        if (bondState == BluetoothDevice.BOND_BONDING) {
            Log.d(TAG, "Bonding already in progress");
            return true;
        }

        try {
            boolean result = device.createBond();
            Log.d(TAG, "createBond requested -> " + result);
            return result;
        } catch (SecurityException e) {
            notifyError("Bonding refusé : permission");
            Log.w(TAG, "createBond SecurityException", e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void discoverServices() {
        if (bluetoothGatt == null) {
            notifyError("GATT non disponible pour discoverServices");
            return;
        }
        try {
            boolean ok = bluetoothGatt.discoverServices();
            Log.d(TAG, "discoverServices requested -> " + ok);
        } catch (SecurityException se) {
            notifyError("discoverServices permission refusée");
            Log.w(TAG, "discoverServices SecurityException", se);
        } catch (Exception e) {
            notifyError("discoverServices error: " + e.getMessage());
            Log.w(TAG, "discoverServices Exception", e);
        }
    }
}