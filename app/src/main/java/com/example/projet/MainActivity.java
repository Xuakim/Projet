package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private static final String TAG = "BLE_DEBUG";

    // --- UUIDs ---
    private static final UUID MIC_CONTROL_SERVICE_UUID = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    private static final UUID MIC_MUTE_UUID = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_CONTROL_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID = UUID.fromString("00002b77-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION_UUID = UUID.fromString("00002b7c-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // --- UI & Data ---
    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> discoveredDevices = new ArrayList<>();
    private TextView dataDisplay;
    private final Map<String, String> characteristicValues = new LinkedHashMap<>();

    // --- BLE Command Queue ---
    private final Queue<Runnable> commandQueue = new LinkedList<>();
    private boolean isCommandQueueProcessing = false;
    private Handler mainHandler;

    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startDiscovery();
                } else {
                    Toast.makeText(this, "L'activation du Bluetooth a été refusée.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Cet appareil ne supporte pas le Bluetooth.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        deviceListView = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevices);
        deviceListView.setAdapter(deviceListAdapter);
        dataDisplay = findViewById(R.id.data_display);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = discoveredDevices.get(position);
            String deviceAddress = deviceInfo.substring(deviceInfo.length() - 17);
            connectToDevice(bluetoothAdapter.getRemoteDevice(deviceAddress));
        });

        registerReceiver(discoveryReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQUEST_PERMISSIONS_CODE);
            } else {
                enableBluetooth();
            }
        } else {
            enableBluetooth();
        }
    }

    private void enableBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                requestEnableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            }
        } else {
            startDiscovery();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableBluetooth();
        }
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                    String deviceName = device.getName();
                    String deviceAddress = device.getAddress();
                    if (deviceName != null && !deviceName.isEmpty()) {
                        String deviceInfo = deviceName + "\n" + deviceAddress;
                        if (!discoveredDevices.contains(deviceInfo)) {
                            discoveredDevices.add(deviceInfo);
                            deviceListAdapter.notifyDataSetChanged();
                        }
                    }
                }
            }
        }
    };

    private void startDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return;
        if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        discoveredDevices.clear();
        deviceListAdapter.notifyDataSetChanged();
        bluetoothAdapter.startDiscovery();
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        }
        deviceListView.setVisibility(View.GONE);
        dataDisplay.setVisibility(View.VISIBLE);
        dataDisplay.setText("Connexion à " + device.getAddress() + "...");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private String getCharacteristicName(UUID uuid) {
        if (MIC_MUTE_UUID.equals(uuid)) return "Mic Mute";
        if (AUDIO_INPUT_DESCRIPTION_UUID.equals(uuid)) return "Audio Input Description";
        return "Unknown Characteristic";
    }

    private void initializeCharacteristicsMap() {
        characteristicValues.clear();
        characteristicValues.put(getCharacteristicName(AUDIO_INPUT_DESCRIPTION_UUID), "En attente...");
        updateDataDisplay();
    }

    private void updateDataDisplay() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : characteristicValues.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n\n");
        }
        runOnUiThread(() -> dataDisplay.setText(sb.toString()));
    }

    private synchronized void processNextCommand() {
        if (isCommandQueueProcessing) return;
        if (commandQueue.isEmpty()) {
            Log.d(TAG, "File de commandes vide.");
            return;
        }
        isCommandQueueProcessing = true;
        commandQueue.poll().run();
    }

    private synchronized void signalCommandProcessed() {
        Log.d(TAG, "Commande terminée, passage à la suivante.");
        isCommandQueueProcessing = false;
        processNextCommand();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connecté au serveur GATT.");
                commandQueue.clear();
                isCommandQueueProcessing = false;
                runOnUiThread(() -> initializeCharacteristicsMap());
                mainHandler.postDelayed(() -> {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.discoverServices();
                    }
                }, 600);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Déconnecté du serveur GATT.");
                commandQueue.clear();
                isCommandQueueProcessing = false;
                runOnUiThread(() -> {
                    dataDisplay.setText("Déconnecté.");
                    deviceListView.setVisibility(View.VISIBLE);
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered a échoué avec le statut: " + status);
                return;
            }
            logAllServices(gatt);

            // --- PHASE 1 : Activer les notifications sur Audio Input Description ---
            BluetoothGattService audioService = gatt.getService(AUDIO_INPUT_CONTROL_SERVICE_UUID);
            if (audioService != null) {
                BluetoothGattCharacteristic descriptionChar = audioService.getCharacteristic(AUDIO_INPUT_DESCRIPTION_UUID);
                if (descriptionChar != null) {
                    // Ajouter la commande pour activer les notifications
                    commandQueue.add(() -> enableNotification(gatt, descriptionChar));
                } else {
                    runOnUiThread(() -> characteristicValues.put(getCharacteristicName(AUDIO_INPUT_DESCRIPTION_UUID), "Caractéristique non trouvée"));
                    updateDataDisplay();
                }
            } else {
                runOnUiThread(() -> characteristicValues.put(getCharacteristicName(AUDIO_INPUT_DESCRIPTION_UUID), "Service 1843 non trouvé"));
                updateDataDisplay();
            }
            processNextCommand();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notifications activées avec succès pour " + descriptor.getCharacteristic().getUuid());
                runOnUiThread(() -> {
                    String key = getCharacteristicName(descriptor.getCharacteristic().getUuid());
                    characteristicValues.put(key, "Notifications activées");
                    updateDataDisplay();
                });
            } else {
                Log.e(TAG, "Échec d'activation des notifications, statut: " + status);
            }
            signalCommandProcessed();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) { // Deprecated
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicRead (deprecated) pour " + characteristic.getUuid());
                handleCharacteristicValue(characteristic.getUuid(), characteristic.getValue());
            } else {
                Log.e(TAG, "onCharacteristicRead (deprecated) a échoué pour " + characteristic.getUuid() + " avec le statut: " + status);
            }
            signalCommandProcessed();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) { // New
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicRead (new) pour " + characteristic.getUuid());
                handleCharacteristicValue(characteristic.getUuid(), value);
            } else {
                Log.e(TAG, "onCharacteristicRead (new) a échoué pour " + characteristic.getUuid() + " avec le statut: " + status);
            }
            signalCommandProcessed();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) { // Deprecated
            Log.d(TAG, "Notification reçue (deprecated) de " + characteristic.getUuid());
            handleCharacteristicValue(characteristic.getUuid(), characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) { // New
            Log.d(TAG, "Notification reçue (new) de " + characteristic.getUuid());
            handleCharacteristicValue(characteristic.getUuid(), value);
        }

        private void enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) {
                Log.e(TAG, "Caractéristique nulle");
                signalCommandProcessed();
                return;
            }

            // Vérifier si la caractéristique supporte les notifications
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                Log.w(TAG, "Caractéristique ne supporte pas NOTIFY: " + characteristic.getUuid());
                signalCommandProcessed();
                return;
            }

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                signalCommandProcessed();
                return;
            }

            // Étape 1 : Activer les notifications localement
            boolean success = gatt.setCharacteristicNotification(characteristic, true);
            if (!success) {
                Log.e(TAG, "Échec de setCharacteristicNotification pour " + characteristic.getUuid());
                signalCommandProcessed();
                return;
            }

            // Étape 2 : Écrire dans le CCCD pour informer le périphérique
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor == null) {
                Log.e(TAG, "CCCD non trouvé pour " + characteristic.getUuid());
                signalCommandProcessed();
                return;
            }

            Log.d(TAG, "Activation des notifications pour " + characteristic.getUuid());

            // Utiliser la nouvelle API ou l'ancienne selon la version d'Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }

        private void readCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) {
                signalCommandProcessed(); return;
            }
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                Log.w(TAG, "Caractéristique non lisible: " + characteristic.getUuid());
                signalCommandProcessed(); return;
            }
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                signalCommandProcessed(); return;
            }
            Log.d(TAG, "Demande de lecture pour " + characteristic.getUuid());
            gatt.readCharacteristic(characteristic);
        }

        private void handleCharacteristicValue(UUID uuid, byte[] data) {
            if (data == null) return;
            String info = "";
            String key = getCharacteristicName(uuid);

            if (AUDIO_INPUT_DESCRIPTION_UUID.equals(uuid)) {
                info = new String(data);
                Log.i(TAG, "Description lue: " + info);
            }
            characteristicValues.put(key, info);
            updateDataDisplay();
        }

        private void logAllServices(BluetoothGatt gatt) {
            if (gatt == null) return;
            Log.d(TAG, "--- Début de la liste des services ---");
            for (BluetoothGattService service : gatt.getServices()) {
                Log.d(TAG, "Service trouvé: " + service.getUuid());
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    Log.d(TAG, "  -> Caractéristique: " + characteristic.getUuid() + " | Propriétés: " + getProperties(characteristic));
                }
            }
            Log.d(TAG, "--- Fin de la liste des services ---");
        }

        private String getProperties(BluetoothGattCharacteristic c) {
            List<String> props = new ArrayList<>();
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) props.add("READ");
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) props.add("WRITE");
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) props.add("NOTIFY");
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) props.add("INDICATE");
            return String.join(", ", props);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }
        unregisterReceiver(discoveryReceiver);
    }
}