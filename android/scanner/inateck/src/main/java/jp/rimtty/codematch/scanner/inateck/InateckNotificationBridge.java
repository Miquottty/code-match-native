package jp.rimtty.codematch.scanner.inateck;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleNotifyCallback;
import com.clj.fastble.callback.BleWriteCallback;
import com.clj.fastble.exception.BleException;
import com.inateck.scanner.ble.BleListManager;
import com.inateck.scanner.ble.BleMessager;
import com.inateck.scanner.ble.BleScannerDevice;

/**
 * Java bridge for the SDK's Kotlin-internal accessors.
 *
 * The published 2.0.0 JAR exposes these methods with `$ble_release` names.
 * Java can call those names directly, while Kotlin correctly treats them as
 * internal to the vendor module. No reflection or binary modification is
 * required.
 */
final class InateckNotificationBridge {
    interface Callback {
        void onReady();
        void onFailure();
        void onCommandTraffic();
        /** A notification the SDK's running command will parse; the bytes are a copy. */
        void onCommandBytes(byte[] value);
        void onBytes(byte[] value);
    }

    interface WriteCallback {
        void onSuccess();
        void onFailure();
    }

    private InateckNotificationBridge() {}

    static void disableVendorLogging() {
        manager().enableLog(false);
    }

    static void install(BleScannerDevice device, Callback callback) {
        manager().notify(
                device.getDevice$ble_release(),
                BleMessager.serviceUUID,
                BleMessager.notifyUUID,
                new BleNotifyCallback() {
                    @Override
                    public void onNotifySuccess() {
                        callback.onReady();
                    }

                    @Override
                    public void onNotifyFailure(BleException exception) {
                        callback.onFailure();
                    }

                    @Override
                    public void onCharacteristicChanged(byte[] data) {
                        byte[] safeCopy = data == null ? new byte[0] : data.clone();
                        if (device.getMessager()
                                .getTaskManager$ble_release()
                                .getRunningTask() != null) {
                            callback.onCommandTraffic();
                            callback.onCommandBytes(safeCopy.clone());
                            // Preserve the SDK's command parser for auth/settings
                            // replies without retaining its raw-byte Logcat call.
                            device.getMessager()
                                    .getTaskManager$ble_release()
                                    .receiveData(safeCopy);
                        } else {
                            callback.onBytes(safeCopy);
                        }
                    }
                });
    }

    static void stop(BleScannerDevice device) {
        manager().stopNotify(
                device.getDevice$ble_release(),
                BleMessager.serviceUUID,
                BleMessager.notifyUUID);
    }

    /**
     * Writes the official SDK-output command to FF04 using the characteristic's
     * required write-without-response mode.
     */
    static void writeSdkOutputCommand(
            BleScannerDevice device,
            byte[] command,
            WriteCallback callback) {
        BluetoothGatt gatt = manager().getBluetoothGatt(device.getDevice$ble_release());
        if (gatt == null) {
            callback.onFailure();
            return;
        }
        BluetoothGattService service = gatt.getService(
                java.util.UUID.fromString(BleMessager.serviceUUID));
        BluetoothGattCharacteristic characteristic = service == null
                ? null
                : service.getCharacteristic(java.util.UUID.fromString(BleMessager.writeUUID));
        if (characteristic == null) {
            callback.onFailure();
            return;
        }
        int properties = characteristic.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            // The Android 2.0.0 SDK itself writes settings to FF04 using the
            // characteristic's default write type. Preserve compatibility
            // with firmware that advertises only that property.
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            callback.onFailure();
            return;
        }
        manager().write(
                device.getDevice$ble_release(),
                BleMessager.serviceUUID,
                BleMessager.writeUUID,
                command.clone(),
                false,
                new BleWriteCallback() {
                    private boolean completed;

                    @Override
                    public void onWriteSuccess(int current, int total, byte[] justWrite) {
                        if (!completed && current == total) {
                            completed = true;
                            callback.onSuccess();
                        }
                    }

                    @Override
                    public void onWriteFailure(BleException exception) {
                        if (!completed) {
                            completed = true;
                            callback.onFailure();
                        }
                    }
                });
    }

    private static BleManager manager() {
        return BleListManager.INSTANCE.getManager$ble_release();
    }
}
