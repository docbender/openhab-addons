/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.teslapowerwall.internal;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.teslapowerwall.internal.api.BatterySOE;
import org.openhab.binding.teslapowerwall.internal.api.GridStatus;
import org.openhab.binding.teslapowerwall.internal.api.MeterAggregates;
import org.openhab.binding.teslapowerwall.internal.api.Operations;
import org.openhab.binding.teslapowerwall.internal.api.SystemStatus;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link TeslaPowerwallHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Paul Smedley - Initial contribution
 */
@NonNullByDefault
public class TeslaPowerwallHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(TeslaPowerwallHandler.class);

    private @NonNullByDefault({}) TeslaPowerwallConfiguration config;
    private @NonNullByDefault({}) TeslaPowerwallWebTargets webTargets;
    private @Nullable ScheduledFuture<?> pollFuture;

    public TeslaPowerwallHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        config = getConfigAs(TeslaPowerwallConfiguration.class);
        webTargets = new TeslaPowerwallWebTargets(config.hostname, httpClient);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.warn("This binding is read only");
    }

    @Override
    public void initialize() {
        config = getConfigAs(TeslaPowerwallConfiguration.class);
        logger.debug("config.hostname = {}, refresh = {}", config.hostname, config.refresh);
        if (config.hostname.isBlank() || config.email.isBlank() || config.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.missing-config-key");
            return;
        } else {
            updateStatus(ThingStatus.UNKNOWN);
            schedulePoll();
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        stopPoll();
    }

    private void schedulePoll() {
        ScheduledFuture<?> pollFuture = this.pollFuture;
        if (pollFuture != null) {
            pollFuture.cancel(false);
        }
        logger.debug("Scheduling poll for every {} s", config.refresh);
        this.pollFuture = scheduler.scheduleWithFixedDelay(this::pollStatus, 0, config.refresh, TimeUnit.SECONDS);
    }

    private void stopPoll() {
        final Future<?> future = pollFuture;
        if (future != null) {
            future.cancel(true);
            pollFuture = null;
        }
    }

    private void pollStatus() {
        Operations operations = null;
        BatterySOE batterySOE = null;
        GridStatus gridStatus = null;
        SystemStatus systemStatus = null;
        MeterAggregates meterAggregates = null;
        try {
            operations = webTargets.getOperations(config.email, config.password);
            batterySOE = webTargets.getBatterySOE(config.email, config.password);
            gridStatus = webTargets.getGridStatus(config.email, config.password);
            systemStatus = webTargets.getSystemStatus(config.email, config.password);
            meterAggregates = webTargets.getMeterAggregates(config.email, config.password);
            updateStatus(ThingStatus.ONLINE);
        } catch (TeslaPowerwallAuthenticationException e) {
            logger.debug("Unexpected authentication error connecting to Tesla Powerwall", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        } catch (TeslaPowerwallCommunicationException e) {
            logger.debug("Unexpected error connecting to Tesla Powerwall", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            return;
        }

        if (operations != null) {
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_MODE,
                    new StringType(operations.realMode));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_RESERVE,
                    new QuantityType<>(((operations.backupReservePercent / 0.95) - (5 / 0.95)), Units.PERCENT));
        }
        if (batterySOE != null) {
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_BATTERY_SOE,
                    new QuantityType<>(((batterySOE.soe / 0.95) - (5 / 0.95)), Units.PERCENT));
        }
        if (gridStatus != null) {
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_GRID_STATUS,
                    new StringType(gridStatus.gridStatus));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_GRID_SERVICES,
                    (gridStatus.gridServicesActive ? OnOffType.ON : OnOffType.OFF));
        }
        if (systemStatus != null) {
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_FULL_PACK_ENERGY,
                    new QuantityType<>(systemStatus.nominalFullPackEnergy, Units.WATT_HOUR));
            if (systemStatus.nominalFullPackEnergy < systemStatus.availableBlocks
                    * TeslaPowerwallBindingConstants.TESLA_POWERWALL_CAPACITY) {
                updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_DEGRADATION, new QuantityType<>(
                        (systemStatus.availableBlocks * (float) TeslaPowerwallBindingConstants.TESLA_POWERWALL_CAPACITY
                                - systemStatus.nominalFullPackEnergy)
                                / (systemStatus.availableBlocks
                                        * TeslaPowerwallBindingConstants.TESLA_POWERWALL_CAPACITY)
                                * 100,
                        Units.PERCENT));
            } else {
                updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_DEGRADATION,
                        new QuantityType<>(0, Units.PERCENT));
            }
            if (systemStatus.batteryBlocks != null && systemStatus.batteryBlocks.length > 0) {
                Map<String, String> properties = editProperties();
                for (int i = 0; i < systemStatus.availableBlocks; i++) {
                    properties.put("Battery" + (i + 1) + "-partno", systemStatus.batteryBlocks[i].packagePartNumber);
                    properties.put("Battery" + (i + 1) + "-serial", systemStatus.batteryBlocks[i].packageSerialNumber);
                    properties.put("Battery" + (i + 1) + "-fullPackEnergy",
                            String.valueOf(systemStatus.batteryBlocks[i].nominalFullPackEnergy) + "kWh");
                    updateProperties(properties);
                }
            }
        }
        if (meterAggregates != null) {
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_GRID_INST_POWER,
                    new QuantityType<>(meterAggregates.siteMeterDetails.instantPower, Units.WATT));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_GRID_ENERGY_EXPORTED,
                    new QuantityType<>(meterAggregates.siteMeterDetails.energyExported, Units.KILOWATT_HOUR));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_GRID_ENERGY_IMPORTED,
                    new QuantityType<>(meterAggregates.siteMeterDetails.energyImported, Units.KILOWATT_HOUR));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_BATTERY_INST_POWER,
                    new QuantityType<>(meterAggregates.batteryMeterDetails.instantPower, Units.WATT));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_BATTERY_ENERGY_EXPORTED,
                    new QuantityType<>(meterAggregates.batteryMeterDetails.energyExported, Units.KILOWATT_HOUR));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_BATTERY_ENERGY_IMPORTED,
                    new QuantityType<>(meterAggregates.batteryMeterDetails.energyImported, Units.KILOWATT_HOUR));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_HOME_INST_POWER,
                    new QuantityType<>(meterAggregates.loadMeterDetails.instantPower, Units.WATT));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_HOME_ENERGY_EXPORTED,
                    new QuantityType<>(meterAggregates.loadMeterDetails.energyExported, Units.KILOWATT_HOUR));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_HOME_ENERGY_IMPORTED,
                    new QuantityType<>(meterAggregates.loadMeterDetails.energyImported, Units.KILOWATT_HOUR));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_SOLAR_INST_POWER,
                    new QuantityType<>(meterAggregates.solarMeterDetails.instantPower, Units.WATT));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_SOLAR_ENERGY_EXPORTED,
                    new QuantityType<>(meterAggregates.solarMeterDetails.energyExported, Units.KILOWATT_HOUR));
            updateState(TeslaPowerwallBindingConstants.CHANNEL_TESLAPOWERWALL_SOLAR_ENERGY_IMPORTED,
                    new QuantityType<>(meterAggregates.solarMeterDetails.energyImported, Units.KILOWATT_HOUR));
        }
    }
}
