package com.example.projet;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

public interface BleEventListener {
    void onScanResult(BluetoothDevice device);
    void onConnected(BluetoothDevice device);
    void onDisconnected(BluetoothDevice device);
    void onServicesDiscovered(BluetoothGatt gatt);
    void onCharacteristicRead(BluetoothGattCharacteristic characteristic);
    void onCharacteristicChanged(BluetoothGattCharacteristic characteristic);
    void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status);
    void onDescriptorRead(BluetoothGattDescriptor descriptor, int status);
    void onError(String message);
}
