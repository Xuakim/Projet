package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;

    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> discoveredDevices = new ArrayList<>();
    private TextView dataDisplay;

    private BleManager bleManager;
    private UiController uiController;

    private final ActivityResultLauncher<android.content.Intent> requestEnableBluetoothLauncher =
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

        uiController = new UiController(this, dataDisplay, deviceListView);
        bleManager = new BleManager(this, uiController);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = discoveredDevices.get(position);
            String deviceAddress = deviceInfo.substring(deviceInfo.length() - 17);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            bleManager.connectToDevice(device);
            deviceListView.setVisibility(View.GONE);
            dataDisplay.setVisibility(View.VISIBLE);
            dataDisplay.setText("Connexion à " + device.getAddress() + "...");
        });

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN},
                        REQUEST_PERMISSIONS_CODE);
            } else {
                enableBluetooth();
            }
        } else {
            enableBluetooth();
        }
    }

    private void enableBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                requestEnableBluetoothLauncher.launch(new android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
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

    private void startDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return;
        if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        discoveredDevices.clear();
        deviceListAdapter.notifyDataSetChanged();
        bluetoothAdapter.startDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleManager.disconnect();
    }
}
