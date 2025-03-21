package com.polidea.rxandroidble2.internal.connection;

import android.util.Log;

import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.DeadObjectException;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.polidea.rxandroidble2.ClientComponent;
import com.polidea.rxandroidble2.ConnectionParameters;
import com.polidea.rxandroidble2.NotificationSetupMode;
import com.polidea.rxandroidble2.PhyPair;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleCustomOperation;
import com.polidea.rxandroidble2.RxBleDeviceServices;
import com.polidea.rxandroidble2.RxBlePhy;
import com.polidea.rxandroidble2.RxBlePhyOption;
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException;
import com.polidea.rxandroidble2.exceptions.BleException;
import com.polidea.rxandroidble2.internal.Priority;
import com.polidea.rxandroidble2.internal.QueueOperation;
import com.polidea.rxandroidble2.internal.RxBlePhyImpl;
import com.polidea.rxandroidble2.internal.RxBlePhyOptionImpl;
import com.polidea.rxandroidble2.internal.operations.OperationsProvider;
import com.polidea.rxandroidble2.internal.serialization.ConnectionOperationQueue;
import com.polidea.rxandroidble2.internal.serialization.QueueReleaseInterface;
import com.polidea.rxandroidble2.internal.util.ByteAssociation;
import com.polidea.rxandroidble2.internal.util.QueueReleasingEmitterWrapper;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import bleshadow.javax.inject.Inject;
import bleshadow.javax.inject.Named;
import bleshadow.javax.inject.Provider;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableSource;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.functions.Action;
import io.reactivex.functions.Function;

@ConnectionScope
public class RxBleConnectionImpl implements RxBleConnection {
    private static final String TAG = "RxBleConnectionImpl";

    private final ConnectionOperationQueue operationQueue;
    final RxBleGattCallback gattCallback;
    final BluetoothGatt bluetoothGatt;
    private final OperationsProvider operationsProvider;
    private final Provider<LongWriteOperationBuilder> longWriteOperationBuilderProvider;
    final Scheduler callbackScheduler;
    private final ServiceDiscoveryManager serviceDiscoveryManager;
    private final NotificationAndIndicationManager notificationIndicationManager;
    private final MtuProvider mtuProvider;
    private final DescriptorWriter descriptorWriter;
    private final IllegalOperationChecker illegalOperationChecker;

    @Inject
    public RxBleConnectionImpl(
            ConnectionOperationQueue operationQueue,
            RxBleGattCallback gattCallback,
            BluetoothGatt bluetoothGatt,
            ServiceDiscoveryManager serviceDiscoveryManager,
            NotificationAndIndicationManager notificationIndicationManager,
            MtuProvider mtuProvider,
            DescriptorWriter descriptorWriter,
            OperationsProvider operationProvider,
            Provider<LongWriteOperationBuilder> longWriteOperationBuilderProvider,
            @Named(ClientComponent.NamedSchedulers.BLUETOOTH_INTERACTION) Scheduler callbackScheduler,
            IllegalOperationChecker illegalOperationChecker
    ) {
        this.operationQueue = operationQueue;
        this.gattCallback = gattCallback;
        this.bluetoothGatt = bluetoothGatt;
        this.serviceDiscoveryManager = serviceDiscoveryManager;
        this.notificationIndicationManager = notificationIndicationManager;
        this.mtuProvider = mtuProvider;
        this.descriptorWriter = descriptorWriter;
        this.operationsProvider = operationProvider;
        this.longWriteOperationBuilderProvider = longWriteOperationBuilderProvider;
        this.callbackScheduler = callbackScheduler;
        this.illegalOperationChecker = illegalOperationChecker;
    }

    @Override
    public LongWriteOperationBuilder createNewLongWriteBuilder() {
        Log.d(TAG, "Creating new long write builder");
        return longWriteOperationBuilderProvider.get();
    }

    @Override
    @RequiresApi(21 /* Build.VERSION_CODES.LOLLIPOP */)
    public Completable requestConnectionPriority(int connectionPriority, long delay, @NonNull TimeUnit timeUnit) {
        if (connectionPriority != BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                && connectionPriority != BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                && connectionPriority != BluetoothGatt.CONNECTION_PRIORITY_HIGH) {
            return Completable.error(
                    new IllegalArgumentException(
                            "Connection priority must have valid value from BluetoothGatt (received "
                                    + connectionPriority + ")"
                    )
            );
        }

        if (delay <= 0) {
            return Completable.error(new IllegalArgumentException("Delay must be bigger than 0"));
        }

        return operationQueue
                .queue(operationsProvider.provideConnectionPriorityChangeOperation(connectionPriority, delay, timeUnit))
                .ignoreElements();
    }

    @Override
    @RequiresApi(21 /* Build.VERSION_CODES.LOLLIPOP */)
    public Single<Integer> requestMtu(int mtu) {
        Log.d(TAG, "Requesting MTU: " + mtu);
        return operationQueue.queue(operationsProvider.provideMtuChangeOperation(mtu)).firstOrError();
    }

    @Override
    public int getMtu() {
        return mtuProvider.getMtu();
    }

    @Override
    @RequiresApi(26 /* Build.VERSION_CODES.O */)
    public Single<PhyPair> readPhy() {
        Log.d(TAG, "Reading PHY");
        return operationQueue.queue(operationsProvider.providePhyReadOperation()).firstOrError();
    }

    @Override
    @RequiresApi(26 /* Build.VERSION_CODES.O */)
    public Single<PhyPair> setPreferredPhy(Set<RxBlePhy> txPhy, Set<RxBlePhy> rxPhy, RxBlePhyOption phyOptions) {
        Set<RxBlePhyImpl> txPhyImpls = RxBlePhyImpl.fromInterface(txPhy);
        Set<RxBlePhyImpl> rxPhyImpls = RxBlePhyImpl.fromInterface(rxPhy);
        RxBlePhyOptionImpl phyOptionImpl = RxBlePhyOptionImpl.fromInterface(phyOptions);
        return operationQueue.queue(operationsProvider.providePhyRequestOperation(txPhyImpls, rxPhyImpls, phyOptionImpl)).firstOrError();
    }

    @Override
    public Single<RxBleDeviceServices> discoverServices() {
        Log.d(TAG, "Discovering services with default timeout");
        return serviceDiscoveryManager.getDiscoverServicesSingle(20L, TimeUnit.SECONDS);
    }

    @Override
    public Single<RxBleDeviceServices> discoverServices(long timeout, @NonNull TimeUnit timeUnit) {
        Log.d(TAG, "Discovering services with timeout: " + timeout + " " + timeUnit);
        return serviceDiscoveryManager.getDiscoverServicesSingle(timeout, timeUnit);
    }

    @Override
    @Deprecated
    public Single<BluetoothGattCharacteristic> getCharacteristic(@NonNull final UUID characteristicUuid) {
        return discoverServices()
                .flatMap(new Function<RxBleDeviceServices, Single<? extends BluetoothGattCharacteristic>>() {
                    @Override
                    public Single<? extends BluetoothGattCharacteristic> apply(RxBleDeviceServices rxBleDeviceServices) {
                        return rxBleDeviceServices.getCharacteristic(characteristicUuid);
                    }
                });
    }

    @Override
    public Observable<Observable<byte[]>> setupNotification(@NonNull UUID characteristicUuid) {
        Log.d(TAG, "Setting up notification for characteristic: " + characteristicUuid);
        return setupNotification(characteristicUuid, NotificationSetupMode.DEFAULT);
    }

    @Override
    public Observable<Observable<byte[]>> setupNotification(@NonNull BluetoothGattCharacteristic characteristic) {
        Log.d(TAG, "Setting up notification for characteristic: " + characteristic.getUuid());
        return setupNotification(characteristic, NotificationSetupMode.DEFAULT);
    }

    @Override
    public Observable<Observable<byte[]>> setupNotification(@NonNull UUID characteristicUuid,
                                                            @NonNull final NotificationSetupMode setupMode) {
        return getCharacteristic(characteristicUuid)
                .flatMapObservable(new Function<BluetoothGattCharacteristic, ObservableSource<? extends Observable<byte[]>>>() {
                    @Override
                    public Observable<? extends Observable<byte[]>> apply(BluetoothGattCharacteristic characteristic) {
                        return setupNotification(characteristic, setupMode);
                    }
                });
    }

    @Override
    public Observable<Observable<byte[]>> setupNotification(@NonNull BluetoothGattCharacteristic characteristic,
                                                            @NonNull NotificationSetupMode setupMode) {
        return illegalOperationChecker.checkAnyPropertyMatches(characteristic, PROPERTY_NOTIFY)
                .andThen(notificationIndicationManager.setupServerInitiatedCharacteristicRead(characteristic, setupMode, false));
    }

    @Override
    public Observable<Observable<byte[]>> setupIndication(@NonNull UUID characteristicUuid) {
        Log.d(TAG, "Setting up indication for characteristic: " + characteristicUuid);
        return setupIndication(characteristicUuid, NotificationSetupMode.DEFAULT);
    }

    @Override
    public Observable<Observable<byte[]>> setupIndication(@NonNull BluetoothGattCharacteristic characteristic) {
        Log.d(TAG, "Setting up indication for characteristic: " + characteristic.getUuid());
        return setupIndication(characteristic, NotificationSetupMode.DEFAULT);
    }

    @Override
    public Observable<Observable<byte[]>> setupIndication(@NonNull UUID characteristicUuid,
                                                          @NonNull final NotificationSetupMode setupMode) {
        return getCharacteristic(characteristicUuid)
                .flatMapObservable(new Function<BluetoothGattCharacteristic, ObservableSource<? extends Observable<byte[]>>>() {
                    @Override
                    public Observable<? extends Observable<byte[]>> apply(BluetoothGattCharacteristic characteristic) {
                        return setupIndication(characteristic, setupMode);
                    }
                });
    }

    @Override
    public Observable<Observable<byte[]>> setupIndication(@NonNull BluetoothGattCharacteristic characteristic,
                                                          @NonNull NotificationSetupMode setupMode) {
        return illegalOperationChecker.checkAnyPropertyMatches(characteristic, PROPERTY_INDICATE)
                .andThen(notificationIndicationManager.setupServerInitiatedCharacteristicRead(characteristic, setupMode, true));
    }

    @Override
    public Single<byte[]> readCharacteristic(@NonNull UUID characteristicUuid) {
        Log.d(TAG, "Reading characteristic: " + characteristicUuid);
        return getCharacteristic(characteristicUuid)
                .flatMap(new Function<BluetoothGattCharacteristic, SingleSource<? extends byte[]>>() {
                    @Override
                    public SingleSource<? extends byte[]> apply(BluetoothGattCharacteristic characteristic) {
                        return readCharacteristic(characteristic);
                    }
                });
    }

    @Override
    public Single<byte[]> readCharacteristic(@NonNull BluetoothGattCharacteristic characteristic) {
        Log.d(TAG, "Reading characteristic: " + characteristic.getUuid());
        return illegalOperationChecker.checkAnyPropertyMatches(characteristic, PROPERTY_READ)
                .andThen(operationQueue.queue(operationsProvider.provideReadCharacteristic(characteristic)))
                .firstOrError();
    }

    @Override
    public Single<byte[]> writeCharacteristic(@NonNull UUID characteristicUuid, @NonNull final byte[] data) {
        Log.d(TAG, "Writing to characteristic: " + characteristicUuid + ", data length: " + data.length);
        return getCharacteristic(characteristicUuid)
                .flatMap(new Function<BluetoothGattCharacteristic, SingleSource<? extends byte[]>>() {
                    @Override
                    public SingleSource<? extends byte[]> apply(BluetoothGattCharacteristic characteristic) {
                        return writeCharacteristic(characteristic, data);
                    }
                });
    }

    @Override
    public Single<byte[]> writeCharacteristic(@NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] data) {
        Log.d(TAG, "Writing to characteristic: " + characteristic.getUuid() + ", data length: " + data.length);
        return illegalOperationChecker.checkAnyPropertyMatches(
                        characteristic,
                        PROPERTY_WRITE | PROPERTY_WRITE_NO_RESPONSE | PROPERTY_SIGNED_WRITE
                ).andThen(operationQueue.queue(operationsProvider.provideWriteCharacteristic(characteristic, data)))
                .firstOrError();
    }

    @Override
    public Single<byte[]> readDescriptor(@NonNull final UUID serviceUuid, @NonNull final UUID characteristicUuid,
                                         @NonNull final UUID descriptorUuid) {
        Log.d(TAG, "Reading descriptor - service: "
                + serviceUuid
                + ", characteristic: "
                + characteristicUuid
                + ", descriptor: "
                + descriptorUuid
        );

        return discoverServices()
                .flatMap(new Function<RxBleDeviceServices, SingleSource<BluetoothGattDescriptor>>() {
                    @Override
                    public SingleSource<BluetoothGattDescriptor> apply(RxBleDeviceServices rxBleDeviceServices) {
                        return rxBleDeviceServices.getDescriptor(serviceUuid, characteristicUuid, descriptorUuid);
                    }
                })
                .flatMap(new Function<BluetoothGattDescriptor, SingleSource<byte[]>>() {
                    @Override
                    public SingleSource<byte[]> apply(BluetoothGattDescriptor descriptor) {
                        return readDescriptor(descriptor);
                    }
                });
    }

    @Override
    public Single<byte[]> readDescriptor(@NonNull BluetoothGattDescriptor descriptor) {
        Log.d(TAG, "Reading descriptor: "
                + descriptor.getUuid()
                + " from characteristic: "
                + descriptor.getCharacteristic().getUuid()
        );
        return operationQueue
                .queue(operationsProvider.provideReadDescriptor(descriptor))
                .firstOrError()
                .map(new Function<ByteAssociation<BluetoothGattDescriptor>, byte[]>() {
                    @Override
                    public byte[] apply(ByteAssociation<BluetoothGattDescriptor> bluetoothGattDescriptorPair) {
                        return bluetoothGattDescriptorPair.second;
                    }
                });
    }

    @Override
    public Completable writeDescriptor(
            @NonNull final UUID serviceUuid, @NonNull final UUID characteristicUuid, @NonNull final UUID descriptorUuid,
            @NonNull final byte[] data
    ) {
        Log.d(TAG, "Writing descriptor - service: "
                + serviceUuid
                + ", characteristic: "
                + characteristicUuid
                + ", descriptor: "
                + descriptorUuid
                + ", data length: "
                + data.length
        );
        return discoverServices()
                .flatMap(new Function<RxBleDeviceServices, SingleSource<BluetoothGattDescriptor>>() {
                    @Override
                    public SingleSource<BluetoothGattDescriptor> apply(RxBleDeviceServices rxBleDeviceServices) {
                        return rxBleDeviceServices.getDescriptor(serviceUuid, characteristicUuid, descriptorUuid);
                    }
                })
                .flatMapCompletable(new Function<BluetoothGattDescriptor, CompletableSource>() {
                    @Override
                    public CompletableSource apply(BluetoothGattDescriptor bluetoothGattDescriptor) {
                        return writeDescriptor(bluetoothGattDescriptor, data);
                    }
                });
    }

    @Override
    public Completable writeDescriptor(@NonNull BluetoothGattDescriptor bluetoothGattDescriptor, @NonNull byte[] data) {
        Log.d(TAG, "Writing descriptor: "
                + bluetoothGattDescriptor.getUuid()
                + " from characteristic: "
                + bluetoothGattDescriptor.getCharacteristic().getUuid()
                + ", data length: "
                + data.length
        );
        return descriptorWriter.writeDescriptor(bluetoothGattDescriptor, data);
    }

    @Override
    public Single<Integer> readRssi() {
        Log.d(TAG, "Reading RSSI");
        return operationQueue.queue(operationsProvider.provideRssiReadOperation()).firstOrError();
    }

    @Override
    public Observable<ConnectionParameters> observeConnectionParametersUpdates() {
        return gattCallback.getConnectionParametersUpdates();
    }

    @Override
    public <T> Observable<T> queue(@NonNull final RxBleCustomOperation<T> operation) {
        Log.d(TAG, "Queuing custom operation with normal priority");
        return queue(operation, Priority.NORMAL);
    }

    @Override
    public <T> Observable<T> queue(@NonNull final RxBleCustomOperation<T> operation, @NonNull final Priority priority) {
        Log.d(TAG, "Queuing custom operation with priority: " + priority);
        return operationQueue.queue(new QueueOperation<T>() {
            @Override
            @SuppressWarnings("ConstantConditions")
            protected void protectedRun(final ObservableEmitter<T> emitter, final QueueReleaseInterface queueReleaseInterface)
                    throws Throwable {
                final Observable<T> operationObservable;

                try {
                    operationObservable = operation.asObservable(bluetoothGatt, gattCallback, callbackScheduler);
                } catch (Throwable throwable) {
                    queueReleaseInterface.release();
                    throw throwable;
                }

                if (operationObservable == null) {
                    queueReleaseInterface.release();
                    throw new IllegalArgumentException("The custom operation asObservable method must return a non-null observable");
                }

                final QueueReleasingEmitterWrapper<T> emitterWrapper = new QueueReleasingEmitterWrapper<>(emitter, queueReleaseInterface);
                operationObservable
                        .doOnTerminate(clearNativeCallbackReferenceAction())
                        .subscribe(emitterWrapper);
            }

            /**
             * The Native Callback abstractions is intended to be used only in a custom operation, therefore, to make sure
             * that we won't leak any references it's a good idea to clean it.
             */
            private Action clearNativeCallbackReferenceAction() {
                return new Action() {
                    @Override
                    public void run() {
                        gattCallback.setNativeCallback(null);
                        gattCallback.setHiddenNativeCallback(null);
                    }
                };
            }

            @Override
            protected BleException provideException(DeadObjectException deadObjectException) {
                return new BleDisconnectedException(deadObjectException, bluetoothGatt.getDevice().getAddress(),
                        BleDisconnectedException.UNKNOWN_STATUS);
            }

            @Override
            public Priority definedPriority() {
                return priority;
            }
        });
    }
}
