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

    // UUIDs utilisés
    public static final UUID MCS_SERVICE = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    public static final UUID MCS_MUTE = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // File d'attente pour les opérations BLE (évite la surcharge)
    private final Queue<Runnable> bleOperationQueue = new LinkedList<>();
    private boolean isOperationInProgress = false;

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

    // --- Scan ---
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

    // --- Connect / Disconnect ---
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
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {
                notifyError("Permission refusée pour déconnecter");
                Log.w(TAG, "disconnect SecurityException", e);
            } finally {
                bluetoothGatt = null;
            }
        }
    }

    public synchronized BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }

    // --- File d'attente BLE (évite la surcharge) ---
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
        }, 150); // Délai de 150ms entre chaque opération BLE
    }

    // --- Read / Write / Notifications ---
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
            if (gatt == null || characteristic == null) {
                onOperationCompleted();
                return;
            }
            if (!hasConnectPermission()) {
                notifyError("Permission BLUETOOTH_CONNECT manquante pour écriture");
                onOperationCompleted();
                return;
            }
            characteristic.setValue(value);
            try {
                boolean ok = gatt.writeCharacteristic(characteristic);
                Log.d(TAG, "writeCharacteristic " + characteristic.getUuid() + " -> " + ok);
                if (!ok) onOperationCompleted();
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

    // --- GATT callback ---
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = (gatt != null) ? gatt.getDevice() : null;
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "GATT connecté");
                serviceDiscoveryRetries = 0;
                mainHandler.post(() -> listener.onConnected(device));
                try {
                    final BluetoothGatt localGatt = gatt;
                    mainHandler.postDelayed(() -> {
                        try {
                            Log.d(TAG, "Lancement delayed de discoverServices()");
                            if (localGatt != null) localGatt.discoverServices();
                        } catch (SecurityException e) {
                            notifyError("discoverServices permission refusée");
                            Log.w(TAG, "discoverServices SecurityException", e);
                        }
                    }, 500);
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
                notifyError("Services discovery failed: " + status);
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
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            onOperationCompleted();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> listener.onCharacteristicRead(characteristic));
            } else {
                notifyError("Characteristic read failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            mainHandler.post(() -> listener.onCharacteristicChanged(characteristic));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            onOperationCompleted();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("Characteristic write failed: " + status);
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

    // --- Helpers ---
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