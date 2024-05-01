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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.BluetoothCompanyIdentifiers;
import org.openhab.binding.bluetooth.discovery.BluetoothDiscoveryDevice;
import org.openhab.binding.bluetooth.discovery.BluetoothDiscoveryParticipant;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BroadcasterDiscoveryParticipant} is responsible for discovery.
 *
 * @author VitaTucek - Initial contribution
 */
@NonNullByDefault
@Component
public class BroadcasterDiscoveryParticipant implements BluetoothDiscoveryParticipant {
    private final Logger logger = LoggerFactory.getLogger(BroadcasterDiscoveryParticipant.class);

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Set.of(BroadcasterBindingConstants.THING_TYPE_BROADCASTER);
    }

    @Override
    public @Nullable ThingUID getThingUID(BluetoothDiscoveryDevice device) {
        return new ThingUID(BroadcasterBindingConstants.THING_TYPE_BROADCASTER, device.getAdapter().getUID(),
                device.getAddress().toString().toLowerCase().replace(":", ""));
    }

    @Override
    public @Nullable DiscoveryResult createResult(BluetoothDiscoveryDevice device) {
        ThingUID thingUID = getThingUID(device);
        if (thingUID == null) {
            return null;
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(BluetoothBindingConstants.CONFIGURATION_ADDRESS, device.getAddress().toString());

        String label = device.getName();
        if (label == null || label.length() == 0 || label.equals(device.getAddress().toString().replace(':', '-'))) {
            label = "Broadcaster";
        }

        if (device.getManufacturerId() != null) {
            String manufacturer = BluetoothCompanyIdentifiers.get(device.getManufacturerId());
            if (manufacturer != null) {
                properties.put(Thing.PROPERTY_VENDOR, manufacturer);
                label += " (" + manufacturer + ")";
            } else {
                manufacturer = Integer.toHexString(device.getManufacturerId());
                properties.put(Thing.PROPERTY_VENDOR, manufacturer);
                label += " (" + manufacturer + ")";
            }
        }
        Integer txPower = device.getTxPower();
        if (txPower != null) {
            properties.put(BluetoothBindingConstants.PROPERTY_TXPOWER, Integer.toString(txPower));
        }

        var services = device.getServices();

        addPropertyIfPresent(properties, Thing.PROPERTY_MODEL_ID, device.getModel());
        addPropertyIfPresent(properties, Thing.PROPERTY_SERIAL_NUMBER, device.getSerialNumber());
        addPropertyIfPresent(properties, Thing.PROPERTY_HARDWARE_VERSION, device.getHardwareRevision());
        addPropertyIfPresent(properties, Thing.PROPERTY_FIRMWARE_VERSION, device.getFirmwareRevision());
        addPropertyIfPresent(properties, BluetoothBindingConstants.PROPERTY_SOFTWARE_VERSION,
                device.getSoftwareRevision());

        // Create the discovery result and add to the inbox
        return DiscoveryResultBuilder.create(thingUID).withProperties(properties)
                .withRepresentationProperty(BluetoothBindingConstants.CONFIGURATION_ADDRESS)
                .withBridge(device.getAdapter().getUID()).withLabel(label).build();
    }

    private static void addPropertyIfPresent(Map<String, Object> properties, String key, @Nullable Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    @Override
    public boolean requiresConnection(BluetoothDiscoveryDevice device) {
        return false;
    }

    @Override
    public int order() {
        // we want to go last
        return Integer.MAX_VALUE;
    }
}
