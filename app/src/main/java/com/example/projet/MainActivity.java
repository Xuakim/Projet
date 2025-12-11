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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
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

    // --- UI Elements ---
    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> discoveredDevices = new ArrayList<>();
    private ScrollView controlsScrollView;
    private SwitchMaterial switchMicMute;
    private TextView tvAudioDescription;
    private TextView tvAudioState;

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

        // Initialize UI components
        deviceListView = findViewById(R.id.device_list);
        controlsScrollView = findViewById(R.id.controls_scroll_view);
        switchMicMute = findViewById(R.id.switch_mic_mute);
        tvAudioDescription = findViewById(R.id.tv_audio_description);
        tvAudioState = findViewById(R.id.tv_audio_state);

        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevices);
        deviceListView.setAdapter(deviceListAdapter);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = discoveredDevices.get(position);
            String deviceAddress = deviceInfo.substring(deviceInfo.length() - 17);
            connectToDevice(bluetoothAdapter.getRemoteDevice(deviceAddress));
        });

        switchMicMute.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) { // Only react to user interaction
                byte[] value = new byte[]{(byte) (isChecked ? 1 : 0)};
                commandQueue.add(() -> writeCharacteristic(bluetoothGatt, MIC_CONTROL_SERVICE_UUID, MIC_MUTE_UUID, value));
                processNextCommand();
            }
        });

        registerReceiver(discoveryReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        checkAndRequestPermissions();
    }

    private void writeCharacteristic(BluetoothGatt bluetoothGatt, UUID micControlServiceUuid, UUID micMuteUuid, byte[] value) {
        if (bluetoothGatt == null) {
            Log.e(TAG, "writeCharacteristic: bluetoothGatt null");
            signalCommandProcessed();
            return;
        }
        if (micControlServiceUuid == null || micMuteUuid == null) {
            Log.e(TAG, "writeCharacteristic: uuid null");
            signalCommandProcessed();
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "writeCharacteristic: missing BLUETOOTH_CONNECT permission");
            signalCommandProcessed();
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(micControlServiceUuid);
        if (service == null) {
            Log.e(TAG, "writeCharacteristic: service not found " + micControlServiceUuid);
            signalCommandProcessed();
            return;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(micMuteUuid);
        if (characteristic == null) {
            Log.e(TAG, "writeCharacteristic: characteristic not found " + micMuteUuid);
            signalCommandProcessed();
            return;
        }

        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                && (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            Log.e(TAG, "writeCharacteristic: characteristic not writable " + micMuteUuid);
            signalCommandProcessed();
            return;
        }

        // Use modern API on TIRAMISU+ which returns request id (int)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int req = bluetoothGatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                Log.d(TAG, "writeCharacteristic (new API) requestId=" + req + " for " + micMuteUuid);
                if (req <= 0) {
                    Log.e(TAG, "writeCharacteristic: request start failed (requestId=" + req + ")");
                    signalCommandProcessed();
                }
            } else {
                characteristic.setValue(value);
                boolean started = bluetoothGatt.writeCharacteristic(characteristic);
                Log.d(TAG, "writeCharacteristic (legacy) started=" + started + " for " + micMuteUuid);
                if (!started) {
                    Log.e(TAG, "writeCharacteristic: start failed");
                    signalCommandProcessed();
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "writeCharacteristic: SecurityException", e);
            signalCommandProcessed();
        } catch (Exception e) {
            Log.e(TAG, "writeCharacteristic: Exception", e);
            signalCommandProcessed();
        }
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
        runOnUiThread(() -> {
            deviceListView.setVisibility(View.GONE);
            controlsScrollView.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Connexion à " + device.getAddress() + "...", Toast.LENGTH_SHORT).show();
        });
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private synchronized void processNextCommand() {
        if (isCommandQueueProcessing) return;
        if (commandQueue.isEmpty()) return;
        isCommandQueueProcessing = true;
        mainHandler.post(commandQueue.poll());
    }

    private synchronized void signalCommandProcessed() {
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
                    deviceListView.setVisibility(View.VISIBLE);
                    controlsScrollView.setVisibility(View.GONE);
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            BluetoothGattService micService = gatt.getService(MIC_CONTROL_SERVICE_UUID);
            if (micService != null) {
                commandQueue.add(() -> subscribeToCharacteristic(gatt, micService.getCharacteristic(MIC_MUTE_UUID)));
            }
            BluetoothGattService audioService = gatt.getService(AUDIO_INPUT_CONTROL_SERVICE_UUID);
            if (audioService != null) {
                commandQueue.add(() -> readCharacteristic(gatt, audioService.getCharacteristic(AUDIO_INPUT_DESCRIPTION_UUID)));
                commandQueue.add(() -> subscribeToCharacteristic(gatt, audioService.getCharacteristic(AUDIO_INPUT_STATE_UUID)));
            }
            processNextCommand();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                commandQueue.add(() -> readCharacteristic(gatt, descriptor.getCharacteristic()));
            }
            signalCommandProcessed();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic.getUuid(), value);
            }
            signalCommandProcessed();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleCharacteristicValue(characteristic.getUuid(), value);
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if(status == BluetoothGatt.GATT_SUCCESS) {
                 Log.d(TAG, "Ecriture OK pour " + characteristic.getUuid());
            } else {
                 Log.e(TAG, "Ecriture échouée pour " + characteristic.getUuid() + " statut: " + status);
            }
            signalCommandProcessed();
        }

        private void subscribeToCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) { signalCommandProcessed(); return; }
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { signalCommandProcessed(); return; }
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor == null) { signalCommandProcessed(); return; }
            byte[] value = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value);
            } else {
                descriptor.setValue(value);
                gatt.writeDescriptor(descriptor);
            }
        }

        private void readCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                 signalCommandProcessed(); return; 
            }
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                 signalCommandProcessed(); return; 
            }
            gatt.readCharacteristic(characteristic);
        }

        private void writeCharacteristic(BluetoothGatt gatt, UUID serviceUuid, UUID charUuid, byte[] value) {
             if (gatt == null) { signalCommandProcessed(); return; }
             BluetoothGattService service = gatt.getService(serviceUuid);
             if (service == null) { signalCommandProcessed(); return; }
             BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
             if (characteristic == null || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                  signalCommandProcessed(); return; 
             }
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                 gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
             } else {
                 characteristic.setValue(value);
                 gatt.writeCharacteristic(characteristic);
             }
        }

        private void handleCharacteristicValue(UUID uuid, byte[] data) {
            if (data == null) return;

            if (MIC_MUTE_UUID.equals(uuid)) {
                if (data.length > 0) {
                    boolean isMuted = data[0] == 1;
                    runOnUiThread(() -> switchMicMute.setChecked(isMuted));
                }
            } else if (AUDIO_INPUT_DESCRIPTION_UUID.equals(uuid)) {
                String description = new String(data);
                runOnUiThread(() -> tvAudioDescription.setText(description));
            } else if (AUDIO_INPUT_STATE_UUID.equals(uuid)) {
                if (data.length >= 3) {
                    String state = String.format("Gain:%d, Mute:%s, Mode:%s", (data[0] & 0xFF), (data[1] == 1 ? "Muet" : "Non muet"), (data[2] == 0 ? "Manual" : "Auto"));
                    runOnUiThread(() -> tvAudioState.setText(state));
                }
            }
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
