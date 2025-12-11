package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;

import java.util.UUID;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
//IMPORT DES PACKAGES NECESSAIRES
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;

    // Déclaration des variables pour la liste des appareils
    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> discoveredDevices = new ArrayList<>();
    private BluetoothGatt bluetoothGatt;
    private TextView dataDisplay;

    // UUIDs pour le service de contrôle d'entrée audio (Microphone Profile)
    // Remplacer si votre appareil utilise des UUIDs personnalisés.
    private static final UUID SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb"); // Audio Input Control Service
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("00002b77-0000-1000-8000-00805f9b34fb"); // Audio Input State
    // UUID pour le Client Characteristic Configuration Descriptor (CCCD)
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activé.", Toast.LENGTH_SHORT).show();
                    startDiscovery();
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

        // Initialisation du BluetoothAdapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Cet appareil ne supporte pas le Bluetooth.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialisation de la ListView et de son adaptateur
        deviceListView = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevices);
        deviceListView.setAdapter(deviceListAdapter);
        dataDisplay = findViewById(R.id.data_display);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = discoveredDevices.get(position);
            String deviceAddress = deviceInfo.substring(deviceInfo.length() - 17);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
            }

            connectToDevice(device);
        });

        // Enregistrer le BroadcastReceiver pour la découverte d'appareils
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);

        // Vérifier et demander les permissions nécessaires
        checkAndRequestPermissions();
    }

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
            Toast.makeText(this, "Le Bluetooth est déjà activé.", Toast.LENGTH_SHORT).show();
            startDiscovery();
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

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        String deviceName = device.getName();
                        String deviceAddress = device.getAddress();
                        String deviceInfo = (deviceName != null ? deviceName : "Appareil inconnu") + "\n" + deviceAddress;

                        if (!discoveredDevices.contains(deviceInfo)) {
                            discoveredDevices.add(deviceInfo);
                            deviceListAdapter.notifyDataSetChanged();
                        }
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Toast.makeText(MainActivity.this, "Recherche terminée.", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private void startDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission BLUETOOTH_SCAN requise pour la recherche.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (bluetoothAdapter.isDiscovering()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter.cancelDiscovery();
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter.startDiscovery()) {
                Toast.makeText(this, "Recherche des appareils en cours...", Toast.LENGTH_SHORT).show();
                discoveredDevices.clear();
                deviceListAdapter.notifyDataSetChanged();
            } else {
                Toast.makeText(this, "Erreur lors du démarrage de la recherche.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connecté au serveur GATT.", Toast.LENGTH_SHORT).show());
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Déconnecté du serveur GATT.", Toast.LENGTH_SHORT).show());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic =
                            service.getCharacteristic(CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this,
                                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                            return;
                        // Étape 4: S'abonner aux notifications
                        gatt.setCharacteristicNotification(characteristic, true);
                        BluetoothGattDescriptor descriptor =
                                characteristic.getDescriptor(CCCD_UUID);

                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    } else {
                        // Caractéristique non trouvée
                    }
                } else {
                    // Service non trouvé
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Abonnement aux notifications réussi.", Toast.LENGTH_SHORT).show());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic) {
            // Étape 4: Les données ont changé, on les affiche
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new
                        StringBuilder(data.length);
                for(byte byteChar : data) {
                    stringBuilder.append(String.format("%02X ",
                            byteChar));
                }
                runOnUiThread(() -> dataDisplay.setText("Données reçues: " + stringBuilder.toString()));
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }

        if (bluetoothAdapter != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
            }
        }

        try {
            unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
    }
}