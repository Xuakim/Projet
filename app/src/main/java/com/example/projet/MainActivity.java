package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean scanning;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> discoveredDevices = new ArrayList<>();
    private final List<BluetoothDevice> discoveredBtDevices = new ArrayList<>();
    private BluetoothGatt bluetoothGatt;
    private TextView dataDisplay;

    private static final long SCAN_PERIOD = 10000; // 10 secondes

    // --- UUIDs pour le profil AICS (Audio Input Control Service) ---
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTING_PROPERTIES_UUID = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE_UUID = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS_UUID = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_CONTROL_POINT_UUID = UUID.fromString("00002B7B-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION_UUID = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // File d'attente pour les opérations de lecture/écriture GATT
    private final Queue<BluetoothGattCharacteristic> characteristicReadQueue = new LinkedList<>();

    // Variables pour stocker les états des caractéristiques
    private String micMuteState = "N/A";
    private String micGainState = "N/A";
    private String micGainModeState = "N/A";
    private String micGainProperties = "N/A";
    private String micInputType = "N/A";
    private String micStatus = "N/A";
    private String micDescription = "N/A";

    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activé.", Toast.LENGTH_SHORT).show();
                    scanLeDevice(true);
                } else {
                    Toast.makeText(this, "L'activation du Bluetooth a été refusée.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Cet appareil ne supporte pas le Bluetooth.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        deviceListView = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevices);
        deviceListView.setAdapter(deviceListAdapter);
        dataDisplay = findViewById(R.id.data_display);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            final BluetoothDevice device = discoveredBtDevices.get(position);
            if (device == null) return;

            scanLeDevice(false); // Arrêter la recherche avant de se connecter
            connectToDevice(device);
        });

        checkAndRequestPermissions();
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> dataDisplay.setText("Connexion à " + device.getName() + "..."));
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private void scanLeDevice(final boolean enable) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission BLUETOOTH_SCAN non accordée.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (enable) {
            handler.postDelayed(() -> {
                if (scanning) {
                    scanning = false;
                    bluetoothLeScanner.stopScan(leScanCallback);
                    Toast.makeText(this, "Fin de la recherche BLE.", Toast.LENGTH_SHORT).show();
                }
            }, SCAN_PERIOD);

            scanning = true;
            discoveredDevices.clear();
            discoveredBtDevices.clear();
            deviceListAdapter.notifyDataSetChanged();
            bluetoothLeScanner.startScan(leScanCallback);
            Toast.makeText(this, "Recherche BLE en cours...", Toast.LENGTH_SHORT).show();
        } else {
            if (scanning) {
                scanning = false;
                bluetoothLeScanner.stopScan(leScanCallback);
            }
        }
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            String deviceName = device.getName();
            if (deviceName != null && !deviceName.isEmpty()) {
                String deviceAddress = device.getAddress();
                String deviceInfo = deviceName + "\n" + deviceAddress;
                if (!discoveredBtDevices.contains(device)) {
                    discoveredDevices.add(deviceInfo);
                    discoveredBtDevices.add(device);
                    deviceListAdapter.notifyDataSetChanged();
                }
            }
        }
    };

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
        } else {
            enableBluetooth();
        }
    }

    private void enableBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    requestEnableBluetoothLauncher.launch(enableBtIntent);
                } else {
                    Toast.makeText(this, "Permission BLUETOOTH_CONNECT requise.", Toast.LENGTH_LONG).show();
                }
            } else {
                requestEnableBluetoothLauncher.launch(enableBtIntent);
            }
        } else {
            scanLeDevice(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allPermissionsGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                Toast.makeText(this, "Permissions accordées.", Toast.LENGTH_SHORT).show();
                enableBluetooth();
            } else {
                Toast.makeText(this, "Permissions Bluetooth refusées.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // Met à jour l'UI avec toutes les données collectées
    private void displayAllData() {
        final String formattedData = "--- État du Microphone ---\n" +
                "Mute: " + micMuteState + "\n" +
                "Gain: " + micGainState + "\n" +
                "Mode de gain: " + micGainModeState + "\n" +
                "Propriétés de gain: " + micGainProperties + "\n" +
                "Type d'entrée: " + micInputType + "\n" +
                "Statut: " + micStatus + "\n" +
                "Description: " + micDescription;

        runOnUiThread(() -> dataDisplay.setText(formattedData));
    }

    // Active les notifications pour une caractéristique donnée
    private void setNotificationForCharacteristic(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        bluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
        }
    }


    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connecté au serveur GATT", Toast.LENGTH_SHORT).show());
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    characteristicReadQueue.clear();
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Déconnecté du serveur GATT.", Toast.LENGTH_SHORT).show();
                    dataDisplay.setText("En attente de données...");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            BluetoothGattService service = gatt.getService(AICS_SERVICE_UUID);
            if (service == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Service de microphone non trouvé", Toast.LENGTH_SHORT).show());
                return;
            }

            // Ajouter toutes les caractéristiques à lire dans la file d'attente
            BluetoothGattCharacteristic gainPropertiesChar = service.getCharacteristic(GAIN_SETTING_PROPERTIES_UUID);
            if (gainPropertiesChar != null) characteristicReadQueue.add(gainPropertiesChar);

            BluetoothGattCharacteristic inputTypeChar = service.getCharacteristic(AUDIO_INPUT_TYPE_UUID);
            if (inputTypeChar != null) characteristicReadQueue.add(inputTypeChar);

            BluetoothGattCharacteristic inputStatusChar = service.getCharacteristic(AUDIO_INPUT_STATUS_UUID);
            if (inputStatusChar != null) characteristicReadQueue.add(inputStatusChar);

            BluetoothGattCharacteristic inputDescriptionChar = service.getCharacteristic(AUDIO_INPUT_DESCRIPTION_UUID);
            if (inputDescriptionChar != null) characteristicReadQueue.add(inputDescriptionChar);

            BluetoothGattCharacteristic inputStateChar = service.getCharacteristic(AUDIO_INPUT_STATE_UUID);
            if (inputStateChar != null) characteristicReadQueue.add(inputStateChar);

            // Commencer par s'abonner aux notifications pour l'état principal
            if (inputStateChar != null) {
                setNotificationForCharacteristic(inputStateChar, true);
            } else {
                // S'il n'y a pas de notif à activer, on commence les lectures
                if (!characteristicReadQueue.isEmpty()) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                    gatt.readCharacteristic(characteristicReadQueue.poll());
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
             // Une fois l'abonnement terminé, on commence à lire les caractéristiques en file d'attente
            if (!characteristicReadQueue.isEmpty()) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                gatt.readCharacteristic(characteristicReadQueue.poll());
            }
        }
        
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseCharacteristic(characteristic);
            }

            // Lire la caractéristique suivante dans la file
            if (!characteristicReadQueue.isEmpty()) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                gatt.readCharacteristic(characteristicReadQueue.poll());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Une caractéristique notifiée a changé
            parseCharacteristic(characteristic);
        }

        private void parseCharacteristic(final BluetoothGattCharacteristic characteristic) {
            UUID uuid = characteristic.getUuid();
            byte[] data = characteristic.getValue();
            if (data == null) return;

            if (AUDIO_INPUT_STATE_UUID.equals(uuid) && data.length >= 3) {
                final int gain = data[0]; // sint8
                final int mute = data[1] & 0xFF; // uint8
                final int gainMode = data[2] & 0xFF; // uint8
                micGainState = gain + " dB";
                micMuteState = (mute == 1) ? "Mute" : "Non Mute";
                micGainModeState = (gainMode == 1) ? "Automatique" : "Manuel";
            } else if (GAIN_SETTING_PROPERTIES_UUID.equals(uuid) && data.length >= 3) {
                micGainProperties = String.format("Unit: %d, Min: %d, Max: %d", data[0] & 0xFF, data[1], data[2]);
            } else if (AUDIO_INPUT_TYPE_UUID.equals(uuid) && data.length > 0) {
                micInputType = ((data[0] & 0xFF) == 0x07) ? "Microphone" : "Autre";
            } else if (AUDIO_INPUT_STATUS_UUID.equals(uuid) && data.length > 0) {
                micStatus = ((data[0] & 0xFF) == 0x01) ? "Actif" : "Inactif";
            } else if (AUDIO_INPUT_DESCRIPTION_UUID.equals(uuid)) {
                micDescription = new String(data, StandardCharsets.UTF_8);
            }

            displayAllData();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanLeDevice(false);
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }
    }
}