/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bluetooth.broadcaster.internal;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.measure.MetricPrefix;
import javax.measure.quantity.Time;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BeaconBluetoothHandler;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.notification.BluetoothScanNotification;
import org.openhab.bluetooth.gattparser.BluetoothGattParser;
import org.openhab.bluetooth.gattparser.BluetoothGattParserFactory;
import org.openhab.bluetooth.gattparser.spec.Characteristic;
import org.openhab.bluetooth.gattparser.spec.Field;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.util.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BroadcasterHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author VitaTucek - Initial contribution
 */
@NonNullByDefault
public class BroadcasterHandler extends BeaconBluetoothHandler {

    private final Logger logger = LoggerFactory.getLogger(BroadcasterHandler.class);

    private @Nullable BroadcasterConfiguration config;

    private final AtomicBoolean receivedStatus = new AtomicBoolean();
    private @NonNullByDefault({}) ScheduledFuture<?> heartbeatFuture;
    private static final int HEARTBEAT_TIMEOUT_MINUTES = 1;
    private final BluetoothGattParser gattParser = BluetoothGattParserFactory.getDefault();

    public BroadcasterHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        super.initialize();

        // updateProperty(BroadcasterBindingConstants.PROPERTY_BINDING_VERSION, "4.2.0");
        logger.debug("{} - initialize. Channels count={}", thing.getLabel(), thing.getChannels().size());
        for (Channel channel : thing.getChannels()) {
            logger.debug("  {}|{}", channel.getLabel(), channel.getDescription());
        }
        config = getConfigAs(BroadcasterConfiguration.class);
        logger.debug("{} - configuration: Address={}, AutoChannelCreation={}", getThing().getLabel(), config.address,
                config.autoChannelCreation);

        // is OFFLINE -> configuration error -> no heartbeat needed
        if (getThing().getStatus() != ThingStatus.OFFLINE) {
            heartbeatFuture = scheduler.scheduleWithFixedDelay(this::heartbeat, 0, HEARTBEAT_TIMEOUT_MINUTES,
                    TimeUnit.MINUTES);
        }
    }

    /**
     * Check device connection timeout
     */
    private void heartbeat() {
        synchronized (receivedStatus) {
            if (!receivedStatus.getAndSet(false) && getThing().getStatus() == ThingStatus.ONLINE) {
                // getThing().getChannels().stream().map(Channel::getUID).filter(this::isLinked)
                // .forEach(c -> updateState(c, UnDefType.UNDEF));
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        String.format("No data received for %d minute", HEARTBEAT_TIMEOUT_MINUTES));
                scanTime = 0;
            }
        }
    }

    @Override
    public void dispose() {
        try {
            super.dispose();
        } finally {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(true);
                heartbeatFuture = null;
            }
        }
    }

    long scanTime = 0;

    @Override
    public void onScanRecordReceived(BluetoothScanNotification scanNotification) {
        synchronized (receivedStatus) {
            receivedStatus.set(true);
            super.onScanRecordReceived(scanNotification);

            if (scanNotification.getRssi() > Integer.MIN_VALUE) {
                if (scanTime > 0) {
                    long period = System.currentTimeMillis() - scanTime;
                    logger.debug("Advertising period {}ms", period);
                    updateProperty(BroadcasterBindingConstants.PROPERTY_ADVERTISING_INTERVAL, String.valueOf(period));
                    QuantityType<Time> quantity = new QuantityType<Time>(period,
                            Units.SECOND.prefix(MetricPrefix.MILLI));
                    updateState(BroadcasterBindingConstants.CHANNEL_TYPE_INTERVAL, quantity);
                }
                scanTime = System.currentTimeMillis();
            }

            logger.debug("Scan notification for {}/{}", device.getName(), device.getAddress());
            logger.debug("  RSSI={}", scanNotification.getRssi());
            logger.debug("  Mandata={}", scanNotification.getManufacturerData());
            if (scanNotification.getManufacturerData().length != 0) {
                if (config.autoChannelCreation) {
                    ChannelUID channelUID = new ChannelUID(getThing().getUID(), "manufacturer-data");
                    ThingBuilder builder = editThing();
                    if (getThing().getChannel(channelUID) == null) {
                        ChannelTypeUID channelTypeUID = new ChannelTypeUID(BluetoothBindingConstants.BINDING_ID,
                                BroadcasterBindingConstants.CHANNEL_TYPE_RAW);
                        var channel = ChannelBuilder.create(channelUID).withType(channelTypeUID).build();
                        builder.withChannel(channel);
                        updateThing(builder.build());
                    }
                    updateState(channelUID,
                            new StringType(HexUtils.bytesToHex(scanNotification.getManufacturerData())));
                }
            }
            logger.debug("  Services:");
            for (var service : scanNotification.getServiceData().entrySet()) {
                logger.debug("    Service={}/{}", service.getKey(), service.getValue());

                String uuid = service.getKey();
                byte[] data = service.getValue();
                logger.debug("      UUID={}", uuid);
                if (uuid.endsWith("-0000-1000-8000-00805f9b34fb")) {
                    if (uuid.startsWith("0000")) {
                        uuid = uuid.substring(4, 8);
                    } else {
                        uuid = uuid.substring(0, 8);
                    }
                } else if (uuid.endsWith("-8000-00805f9b34fb")) {
                    uuid = uuid.substring(0, 18);
                }
                for (var channel : getThing().getChannels()) {
                    var type = channel.getConfiguration().getProperties()
                            .get(BroadcasterBindingConstants.PARAMETER_BROADCASTER_DATA_TYPE);

                    if (type == null || !type.toString().equals("ServiceData")) {
                        continue;
                    }
                    var channelUuid = channel.getConfiguration().getProperties()
                            .get(BroadcasterBindingConstants.PARAMETER_DATA_UUID);
                    if (!channelUuid.toString().equals(uuid)) {
                        continue;
                    }
                    ChannelTypeUID channelType = channel.getChannelTypeUID();
                    if (channelType == null) {
                        continue;
                    }
                    var index = channel.getConfiguration().getProperties()
                            .get(BroadcasterBindingConstants.PARAMETER_DATA_BEGIN_INDEX);
                    var datalength = channel.getConfiguration().getProperties()
                            .get(BroadcasterBindingConstants.PARAMETER_DATA_LENGTH);
                    var multiplicator = channel.getConfiguration().getProperties()
                            .get(BroadcasterBindingConstants.PARAMETER_MULTIPLICATOR);
                    int indexInt = (index == null) ? 0 : ((BigDecimal) index).intValue();
                    int lengthInt = (datalength == null) ? 2 : ((BigDecimal) datalength).intValue();
                    float multiplicatorFloat = (multiplicator == null) ? 1.0f
                            : ((BigDecimal) multiplicator).floatValue();

                    if (channelType.getId().equals(BroadcasterBindingConstants.CHANNEL_TYPE_NUMBER)) {
                        long value;
                        if (lengthInt == 1) {
                            value = ByteBuffer.wrap(data).get(indexInt);
                        } else if (lengthInt == 2) {
                            value = ByteBuffer.wrap(data).getShort(indexInt);
                        } else if (lengthInt == 4) {
                            value = ByteBuffer.wrap(data).getInt(indexInt);
                        } else if (lengthInt == 8) {
                            value = ByteBuffer.wrap(data).getLong(indexInt);
                        } else {
                            logger.warn("Channel {} has set unsupported data length to {}", channel.getLabel(),
                                    lengthInt);
                            continue;
                        }
                        DecimalType decimalValue;
                        if (multiplicatorFloat != 1.0f) {
                            decimalValue = new DecimalType(value * multiplicatorFloat);
                        } else {
                            decimalValue = new DecimalType(value);
                        }
                        logger.debug("      Configured channel data updated by value {}", decimalValue);
                        updateState(channel.getUID(), decimalValue);
                    } else if (channelType.getId().equals(BroadcasterBindingConstants.CHANNEL_TYPE_RAW)) {
                        int indexTo = data.length;
                        if (lengthInt > 0) {
                            indexTo = indexInt + lengthInt;
                        }
                        StringType value = new StringType(
                                HexUtils.bytesToHex(Arrays.copyOfRange(data, indexInt, indexTo)));

                        logger.debug("      Configured channel data updated by value {}", value);
                        updateState(channel.getUID(), value);
                    }

                    // updateState(channel.getUID(), new QuantityType<>(value, ));
                }

                if (config.autoChannelCreation) {
                    ChannelUID channelUID = new ChannelUID(getThing().getUID(), "service-".concat(uuid));
                    Characteristic gattChar = gattParser.getCharacteristic(uuid);
                    if (gattChar != null) {
                        java.util.List<Field> fields = gattParser.getFields(uuid);
                        logger.debug("      Char={}", gattChar.toString());
                        logger.debug("      Fields total {}", fields.size());
                    } else {
                        logger.debug("      No known fields");

                        ThingBuilder builder = editThing();
                        logger.debug("      Looking for {}", "service-".concat(uuid));
                        logger.debug("      channelUID={}", channelUID.toString());
                        if (getThing().getChannel(channelUID) == null) {
                            logger.debug("      UUID not found. Adding one...");
                            ChannelTypeUID channelTypeUID = new ChannelTypeUID(BluetoothBindingConstants.BINDING_ID,
                                    BroadcasterBindingConstants.CHANNEL_TYPE_RAW);
                            Map<String, String> properties = new HashMap<>();
                            properties.put(BroadcasterBindingConstants.PARAMETER_DATA_UUID, uuid);
                            var channel = ChannelBuilder.create(channelUID).withType(channelTypeUID)
                                    .withProperties(properties).build();
                            // changed = true;
                            builder.withChannel(channel);
                            updateThing(builder.build());
                            logger.debug("      Channel added");
                        } else {
                            logger.debug("      Channel exists");
                        }
                    }
                    updateState(channelUID, new StringType(HexUtils.bytesToHex(data)));
                }
            }
        }

    }

    @Override
    public void onServicesDiscovered() {
        super.onServicesDiscovered();
        logger.debug("Services Discovered for {}", device.getName());
        logger.debug("  Service={}", device.getServices());
    }

    @Override
    public void onCharacteristicUpdate(BluetoothCharacteristic characteristic, byte[] value) {
        super.onCharacteristicUpdate(characteristic, value);
        logger.debug("Characteristic Update for {}", device.getName());
    }
}
